package com.flashbooking.model.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.flashbooking.model.enums.HistoryAction;
import com.flashbooking.model.enums.ReservationStatus;

/** Espelha a tabela append-only reservation_history. */
public record ReservationHistory(
        long id,
        UUID reservationId,
        UUID eventId,
        HistoryAction action,
        ReservationStatus previousStatus,
        ReservationStatus newStatus,
        int quantity,
        String reason,
        String correlationId,
        String instanceId,
        OffsetDateTime createdAt) {
}
