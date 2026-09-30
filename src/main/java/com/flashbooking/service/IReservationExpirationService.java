package com.flashbooking.service;

public interface IReservationExpirationService {

    /**
     * Expira UM lote de reservas PENDING vencidas em uma unica transacao (PLANO.md 5.4).
     *
     * @return quantidade de reservas expiradas
     */
    int expireBatch();

    /**
     * Repete {@link #expireBatch()} enquanto o lote vier cheio, ate esvaziar ou atingir o limite.
     *
     * @return total de reservas expiradas
     */
    int expireAll(int maxIterations);
}
