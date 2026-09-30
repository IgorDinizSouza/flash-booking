package com.flashbooking.service;

import java.util.UUID;

import com.flashbooking.model.dto.request.CreateReservationRequest;
import com.flashbooking.model.dto.response.ReservationResponse;
import com.flashbooking.service.impl.ReservationResult;

public interface IReservationService {

    /** Cria a reserva de forma idempotente, em uma unica transacao (ver PLANO.md 5.1). */
    ReservationResult create(UUID eventId, String idempotencyKey, CreateReservationRequest request);

    /** Leitura direta do banco (sem cache) com status efetivo. */
    ReservationResponse getById(UUID id);

    /**
     * Cancela a reserva (PLANO.md 5.3). Retorna sem erro quando cancelou ou ja estava CANCELLED
     * (idempotente, sem devolver estoque de novo).
     *
     * @throws com.flashbooking.exception.BusinessException RESERVATION_NOT_FOUND ou INVALID_RESERVATION_STATE
     */
    void cancel(UUID id);
}
