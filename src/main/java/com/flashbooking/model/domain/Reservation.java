package com.flashbooking.model.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.flashbooking.model.enums.ReservationStatus;

/**
 * Espelha a tabela reservations. {@code status} e o estado fisico; {@code effectiveStatus} aplica
 * o relogio do banco (PENDING vencida aparece como EXPIRED, sem efeito colateral).
 */
public record Reservation(
        UUID id,
        UUID eventId,
        int quantity,
        ReservationStatus status,
        ReservationStatus effectiveStatus,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
