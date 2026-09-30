package com.flashbooking.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flashbooking.support.AbstractIntegrationTest;

/** Camada de persistencia MyBatis: IdempotencyRepository (INSERT ... ON CONFLICT DO NOTHING, JSONB). */
class IdempotencyMapperTest extends AbstractIntegrationTest {

    @Autowired
    IdempotencyRepository idempotency;

    @Test
    void tryInsertIsTrueForNewKeyAndFalseForExistingOne() {
        String key = "k-" + UUID.randomUUID();

        assertThat(idempotency.tryInsert(key, "hash-1")).isTrue();
        assertThat(idempotency.tryInsert(key, "hash-2")).isFalse();
        // ON CONFLICT DO NOTHING nao sobrescreve o hash original
        assertThat(idempotency.find(key).orElseThrow().requestHash()).isEqualTo("hash-1");
    }

    @Test
    void findIsEmptyForUnknownKey() {
        assertThat(idempotency.find("missing-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void freshKeyHasNullStatusAndResponseUntilCompleted() {
        String key = "k-" + UUID.randomUUID();
        idempotency.tryInsert(key, "h");

        var pending = idempotency.find(key).orElseThrow();
        assertThat(pending.key()).isEqualTo(key);
        assertThat(pending.httpStatus()).isNull();
        assertThat(pending.responseJson()).isNull();
    }

    @Test
    void completeStoresStatusAndJsonResponse() {
        String key = "k-" + UUID.randomUUID();
        idempotency.tryInsert(key, "h");

        idempotency.complete(key, 201, "{\"id\":\"abc\",\"quantity\":2}");

        var done = idempotency.find(key).orElseThrow();
        assertThat(done.httpStatus()).isEqualTo(201);
        // JSONB normaliza espacos, mas preserva o conteudo
        assertThat(done.responseJson()).contains("\"id\"", "\"abc\"", "\"quantity\"");
    }
}
