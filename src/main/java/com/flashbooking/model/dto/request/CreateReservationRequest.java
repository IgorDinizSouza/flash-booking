package com.flashbooking.model.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** A validacao (1..booking.reservation.max-quantity) e dinamica e feita no service. */
public record CreateReservationRequest(
        @Schema(example = "2", description = "Between 1 and booking.reservation.max-quantity (default 10)")
        Integer quantity) {
}
