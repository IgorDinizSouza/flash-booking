package com.flashbooking.model.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.flashbooking.model.domain.Reservation;
import com.flashbooking.model.enums.ReservationStatus;

public record ReservationResponse(
        UUID id,
        UUID eventId,
        int quantity,
        ReservationStatus status,
        Instant expiresAt,
        Instant createdAt) {

    /** Usa o status efetivo (PENDING vencida -> EXPIRED). */
    public static ReservationResponse from(Reservation r) {
        return new ReservationResponse(r.id(), r.eventId(), r.quantity(), r.effectiveStatus(),
                r.expiresAt().toInstant(), r.createdAt().toInstant());
    }
}
