package com.flashbooking.repository;

import java.sql.Types;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flashbooking.model.enums.HistoryAction;
import com.flashbooking.model.enums.ReservationStatus;

/** Tabela append-only: so INSERT, dentro da mesma transacao da mudanca de estado. */
@Repository
public class ReservationHistoryRepository {

    private final JdbcClient jdbc;

    public ReservationHistoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
