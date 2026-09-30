package com.flashbooking.repository;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flashbooking.model.enums.HistoryAction;
import com.flashbooking.model.enums.ReservationStatus;

/** Tabela append-only: so INSERT, dentro da mesma transacao da mudanca de estado. */
@Repository
public class ReservationHistoryRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public ReservationHistoryRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Uma linha de historico de um lote: acao/estados/motivo sao comuns a todas. */
    public record Entry(UUID reservationId, UUID eventId, int quantity) {
    }

    /** Batch insert (uma linha por reserva) na transacao corrente. */
    public void insertBatch(List<Entry> entries, HistoryAction action, ReservationStatus previousStatus,
            ReservationStatus newStatus, String reason, String correlationId, String instanceId) {
        if (entries.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate("INSERT INTO reservation_history (reservation_id, event_id, action, "
                + "previous_status, new_status, quantity, reason, correlation_id, instance_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", entries, entries.size(), (ps, e) -> {
                    ps.setObject(1, e.reservationId());
                    ps.setObject(2, e.eventId());
                    ps.setString(3, action.name());
                    ps.setString(4, previousStatus != null ? previousStatus.name() : null);
                    ps.setString(5, newStatus.name());
                    ps.setInt(6, e.quantity());
                    ps.setString(7, reason);
                    ps.setString(8, correlationId);
                    ps.setString(9, instanceId);
                });
    }

    public void insert(UUID reservationId, UUID eventId, HistoryAction action, ReservationStatus previousStatus,
            ReservationStatus newStatus, int quantity, String reason, String correlationId, String instanceId) {
        jdbc.sql("INSERT INTO reservation_history (reservation_id, event_id, action, previous_status, new_status, "
                + "quantity, reason, correlation_id, instance_id) "
                + "VALUES (:reservationId, :eventId, :action, :previousStatus, :newStatus, :quantity, :reason, "
                + ":correlationId, :instanceId)")
                .param("reservationId", reservationId)
                .param("eventId", eventId)
                .param("action", action.name())
                .param("previousStatus", previousStatus != null ? previousStatus.name() : null, Types.VARCHAR)
                .param("newStatus", newStatus.name())
                .param("quantity", quantity)
                .param("reason", reason)
                .param("correlationId", correlationId, Types.VARCHAR)
                .param("instanceId", instanceId, Types.VARCHAR)
                .update();
    }
}
