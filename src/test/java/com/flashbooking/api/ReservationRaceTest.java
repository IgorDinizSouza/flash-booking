package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractApiTest;
import com.flashbooking.support.StockInvariant;

/** Concorrencia extra sobre o estoque: reservar x cancelar (STK-09/CAN-09), quantities mistas (STK-06), cap 1.000.000 (STK-12). */
class ReservationRaceTest extends AbstractApiTest {

    @Test
    @DisplayName("STK-09/CAN-09 20 cancelamentos + 40 reservas simultaneos em evento esgotado: sem 5xx e sem oversell")
    void reserveAndCancelRaceOnSoldOutEvent() throws Exception {
        int capacity = 20;
        UUID eventId = newEvent(capacity);
        List<UUID> held = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            held.add(reserveOk(eventId, 1));
        }
        assertThat(available(eventId)).isZero();

        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (UUID id : held) {
            tasks.add(() -> delete(id));
        }
        for (int i = 0; i < 40; i++) {
            tasks.add(() -> reserve(eventId, 1));
        }

        var results = runConcurrently(tasks);

        var deletes = results.subList(0, capacity);
        var posts = results.subList(capacity, results.size());
        assertThat(deletes).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT));
        assertThat(posts).allSatisfy(r -> assertThat(r.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT));
        long created = posts.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        assertThat(created).isBetween(0L, (long) capacity);
        assertThat(countByStatus(eventId, "PENDING")).isEqualTo((int) created);
        assertThat(countByStatus(eventId, "CANCELLED")).isEqualTo(capacity);
        assertThat(available(eventId)).isEqualTo(capacity - (int) created);
        assertThat(historyCountForEvent(eventId, "CREATED")).isEqualTo(capacity + (int) created);
        assertThat(historyCountForEvent(eventId, "CANCELLED")).isEqualTo(capacity);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("STK-06 quantities 3 e 2 em paralelo sobre capacity 10: sem oversell e sem 5xx")
    void mixedQuantitiesOnTightCapacity() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID eventId = newEvent(10);
            List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
            List<Integer> quantities = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                quantities.add(3);
                quantities.add(2);
            }
            for (int q : quantities) {
                tasks.add(() -> reserve(eventId, q));
            }

            var results = runConcurrently(tasks);

            assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode()).isIn(HttpStatus.CREATED,
                    HttpStatus.CONFLICT));
            int sold = 0;
            for (int i = 0; i < results.size(); i++) {
                if (results.get(i).getStatusCode() == HttpStatus.CREATED) {
                    sold += quantities.get(i);
                }
            }
            assertThat(sold).isLessThanOrEqualTo(10);
            assertThat(available(eventId)).isEqualTo(10 - sold).isGreaterThanOrEqualTo(0);
            // com 16 tentativas de 2 ou 3 sobre 10, nao sobra lugar para nenhuma delas: o estoque so pode ter
            // restado 1 (10 = 3+3+2+2 fecha em 0; 3+3+3 deixa 1; nenhuma quantidade cabe em 1)
            assertThat(available(eventId)).isLessThanOrEqualTo(1);
            StockInvariant.assertHolds(jdbc, eventId);
        }
    }

    @Test
    @DisplayName("STK-12 capacity 1.000.000 com 100 reservas de 10 em paralelo: available = 999000 (sem overflow)")
    void largestCapacityHasNoOverflow() throws Exception {
        UUID eventId = newEvent(1_000_000);
        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> reserve(eventId, 10));
        }

        var results = runConcurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(available(eventId)).isEqualTo(999_000);
        assertThat(countByStatus(eventId, "PENDING")).isEqualTo(100);
        StockInvariant.assertHolds(jdbc, eventId);
        // e devolver tudo volta exatamente a capacidade
        for (var r : results) {
            assertThat(delete(UUID.fromString(r.getBody().get("id").asText())).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
        }
        assertThat(available(eventId)).isEqualTo(1_000_000);
        StockInvariant.assertHolds(jdbc, eventId);
    }
}
