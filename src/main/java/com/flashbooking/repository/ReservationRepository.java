package com.flashbooking.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flashbooking.exception.BusinessException;
import com.flashbooking.model.domain.Reservation;
import com.flashbooking.model.enums.ErrorCode;
import com.flashbooking.model.enums.ReservationStatus;

@Repository
public class ReservationRepository {

    private static final String FK_VIOLATION = "23503";

    // status efetivo calculado com o relogio do banco (NOW()), sem efeito colateral
    private static final String COLUMNS = "id, event_id, quantity, status, "
            + "CASE WHEN status = 'PENDING' AND expires_at <= NOW() THEN 'EXPIRED' ELSE status END AS effective_status, "
            + "expires_at, created_at, updated_at";

    private final JdbcClient jdbc;

    public ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insere a reserva PENDING; created_at/expires_at vem do banco (RETURNING).
     *
     * @throws BusinessException EVENT_NOT_FOUND se o evento nao existir (violacao de FK, 23503)
     */
    public Reservation insert(UUID id, UUID eventId, int quantity, double ttlSeconds) {
        try {
            return jdbc.sql("INSERT INTO reservations (id, event_id, quantity, status, expires_at) "
                    + "VALUES (:id, :eventId, :quantity, 'PENDING', NOW() + make_interval(secs => :ttl)) "
                    + "RETURNING created_at, expires_at")
                    .param("id", id)
                    .param("eventId", eventId)
                    .param("quantity", quantity)
                    .param("ttl", ttlSeconds)
                    .query((rs, n) -> {
                        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
                        return new Reservation(id, eventId, quantity, ReservationStatus.PENDING,
                                ReservationStatus.PENDING, rs.getObject("expires_at", OffsetDateTime.class),
                                createdAt, createdAt);
                    })
                    .single();
        } catch (DataIntegrityViolationException ex) {
            if (hasSqlState(ex, FK_VIOLATION)) {
                throw new BusinessException(ErrorCode.EVENT_NOT_FOUND);
            }
            throw ex;
        }
    }

    public Optional<Reservation> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM reservations WHERE id = :id")
                .param("id", id)
                .query(ReservationRepository::map)
                .optional();
    }

    /** Reserva que acabou de ser cancelada: dados necessarios para o historico e a devolucao de estoque. */
    public record Cancelled(UUID eventId, int quantity) {
    }

    /**
     * Transicao condicional PENDING -> CANCELLED (PLANO.md 5.3). So quem consegue a transicao devolve
     * estoque; sob concorrencia o UPDATE espera o lock da linha e reavalia o predicado.
     *
     * @return dados da reserva cancelada, ou vazio se 0 linhas (inexistente, ja CANCELLED, EXPIRED ou vencida)
     */
    public Optional<Cancelled> cancelIfPendingAndNotExpired(UUID id) {
        return jdbc.sql("UPDATE reservations SET status = 'CANCELLED', updated_at = NOW() "
                + "WHERE id = :id AND status = 'PENDING' AND expires_at > NOW() RETURNING event_id, quantity")
                .param("id", id)
                .query((rs, n) -> new Cancelled(rs.getObject("event_id", UUID.class), rs.getInt("quantity")))
                .optional();
    }

    /** Reserva vencida travada pelo job de expiracao. */
    public record ExpiredRow(UUID id, UUID eventId, int quantity) {
    }

    /**
     * Trava um lote de reservas PENDING vencidas (PLANO.md 5.4). SKIP LOCKED faz varias instancias
     * dividirem o trabalho sem esperar umas pelas outras; as linhas ficam travadas ate o fim da transacao.
     */
    public List<ExpiredRow> lockExpiredBatch(int limit) {
        return jdbc.sql("SELECT id, event_id, quantity FROM reservations "
                + "WHERE status = 'PENDING' AND expires_at <= NOW() "
                + "ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("limit", limit)
                .query((rs, n) -> new ExpiredRow(rs.getObject("id", UUID.class),
                        rs.getObject("event_id", UUID.class), rs.getInt("quantity")))
                .list();
    }

    /** PENDING -> EXPIRED para linhas ja travadas por {@link #lockExpiredBatch(int)}. */
    public int markExpired(List<UUID> ids) {
        return jdbc.sql("UPDATE reservations SET status = 'EXPIRED', updated_at = NOW() WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(new UUID[0]))
                .update();
    }

    private static boolean hasSqlState(Throwable ex, String sqlState) {
        for (Throwable t = ex; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static Reservation map(ResultSet rs, int rowNum) throws SQLException {
        return new Reservation(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getInt("quantity"),
                ReservationStatus.valueOf(rs.getString("status")),
                ReservationStatus.valueOf(rs.getString("effective_status")),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
