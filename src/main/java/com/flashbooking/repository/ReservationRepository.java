package com.flashbooking.repository;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.dao.DataIntegrityViolationException;

import com.flashbooking.exception.BusinessException;
import com.flashbooking.model.domain.Reservation;
import com.flashbooking.model.enums.ErrorCode;
import com.flashbooking.model.enums.ReservationStatus;

/** SQL em resources/mapper/ReservationRepository.xml. */
@Mapper
public interface ReservationRepository {

    String FK_VIOLATION = "23503";

    /** Timestamps gerados pelo banco no INSERT ... RETURNING. */
    record Inserted(OffsetDateTime createdAt, OffsetDateTime expiresAt) {
    }

    /** Reserva que acabou de ser cancelada: dados necessarios para o historico e a devolucao de estoque. */
    record Cancelled(UUID eventId, int quantity) {
    }

    /** Reserva vencida travada pelo job de expiracao. */
    record ExpiredRow(UUID id, UUID eventId, int quantity) {
    }

    /**
     * Insere a reserva PENDING; created_at/expires_at vem do banco (RETURNING).
     *
     * @throws BusinessException EVENT_NOT_FOUND se o evento nao existir (violacao de FK, 23503)
     */
    default Reservation insert(UUID id, UUID eventId, int quantity, double ttlSeconds) {
        try {
            Inserted row = insertReturning(id, eventId, quantity, ttlSeconds);
            return new Reservation(id, eventId, quantity, ReservationStatus.PENDING, ReservationStatus.PENDING,
                    row.expiresAt(), row.createdAt(), row.createdAt());
        } catch (DataIntegrityViolationException ex) {
            if (hasSqlState(ex, FK_VIOLATION)) {
                throw new BusinessException(ErrorCode.EVENT_NOT_FOUND);
            }
            throw ex;
        }
    }

    Inserted insertReturning(@Param("id") UUID id, @Param("eventId") UUID eventId,
            @Param("quantity") int quantity, @Param("ttl") double ttlSeconds);

    Optional<Reservation> findById(@Param("id") UUID id);

    /**
     * Transicao condicional PENDING -> CANCELLED (PLANO.md 5.3). So quem consegue a transicao devolve
     * estoque; sob concorrencia o UPDATE espera o lock da linha e reavalia o predicado.
     *
     * @return dados da reserva cancelada, ou vazio se 0 linhas (inexistente, ja CANCELLED, EXPIRED ou vencida)
     */
    Optional<Cancelled> cancelIfPendingAndNotExpired(@Param("id") UUID id);

    /**
     * Trava um lote de reservas PENDING vencidas (PLANO.md 5.4). SKIP LOCKED faz varias instancias
     * dividirem o trabalho sem esperar umas pelas outras; as linhas ficam travadas ate o fim da transacao.
     */
    List<ExpiredRow> lockExpiredBatch(@Param("limit") int limit);

    /** PENDING -> EXPIRED para linhas ja travadas por {@link #lockExpiredBatch(int)}. */
    default int markExpired(List<UUID> ids) {
        return markExpiredByIds(ids.toArray(new UUID[0]));
    }

    int markExpiredByIds(@Param("ids") UUID[] ids);

    private static boolean hasSqlState(Throwable ex, String sqlState) {
        for (Throwable t = ex; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
