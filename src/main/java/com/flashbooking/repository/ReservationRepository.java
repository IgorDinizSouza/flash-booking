package com.flashbooking.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
