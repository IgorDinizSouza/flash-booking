package com.flashbooking.service;

import java.util.Optional;
import java.util.UUID;

import com.flashbooking.model.domain.IdempotencyKey;

public interface IIdempotencyService {

    /** SHA-256 hex de "POST|/events/{eventId}/reservations|{"quantity":N}". */
    String requestHash(UUID eventId, int quantity);

    /**
     * Deve rodar dentro da transacao. Tenta reservar a chave: vazio se venceu a disputa; se a chave
     * ja existe, lanca IDEMPOTENCY_KEY_CONFLICT (hash diferente) ou devolve a resposta salva (replay).
     */
    Optional<IdempotencyKey> begin(String key, String requestHash);

    void complete(String key, int httpStatus, String responseJson);
}
