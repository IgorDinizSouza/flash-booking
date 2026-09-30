package com.flashbooking.service.impl;

import com.flashbooking.model.dto.response.ReservationResponse;

/** Resultado de POST reservations: corpo, status HTTP e se foi replay de uma chave ja concluida. */
public record ReservationResult(ReservationResponse body, int httpStatus, boolean replayed) {
}
