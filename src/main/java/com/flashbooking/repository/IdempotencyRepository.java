package com.flashbooking.repository;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flashbooking.model.domain.IdempotencyKey;

@Repository
public class IdempotencyRepository {

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true se a chave foi inserida (venceu a disputa). Sob concorrencia, o INSERT espera a
     *         transacao que detem a mesma chave terminar; false = a chave ja existe (commitada).
     */
    public boolean tryInsert(String key, String requestHash) {
        int rows = jdbc.sql("INSERT INTO idempotency_keys (idempotency_key, request_hash) "
                + "VALUES (:key, :hash) ON CONFLICT (idempotency_key) DO NOTHING")
                .param("key", key)
                .param("hash", requestHash)
                .update();
        return rows == 1;
    }

    public Optional<IdempotencyKey> find(String key) {
        return jdbc.sql("SELECT idempotency_key, request_hash, http_status, CAST(response_json AS text) AS response_json "
                + "FROM idempotency_keys WHERE idempotency_key = :key")
                .param("key", key)
                .query((rs, n) -> new IdempotencyKey(
                        rs.getString("idempotency_key"),
                        rs.getString("request_hash"),
                        (Integer) rs.getObject("http_status"),
                        rs.getString("response_json")))
                .optional();
    }

    public void complete(String key, int httpStatus, String responseJson) {
        jdbc.sql("UPDATE idempotency_keys SET http_status = :status, response_json = CAST(:json AS jsonb) "
                + "WHERE idempotency_key = :key")
                .param("status", httpStatus)
                .param("json", responseJson)
                .param("key", key)
                .update();
    }
}
