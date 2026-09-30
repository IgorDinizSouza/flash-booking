package com.flashbooking.model.domain;

/** Espelha a tabela idempotency_keys; httpStatus/responseJson sao nulos ate a chave ser concluida. */
public record IdempotencyKey(String key, String requestHash, Integer httpStatus, String responseJson) {
}
