package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractIntegrationTest;
import com.flashbooking.support.StockInvariant;

/** Teste basico de zero oversell; o I3 completo (com invariante em toda a suite) e da Fase 7. */
class ReservationConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcClient jdbc;

    @Test
    void twoHundredParallelRequestsOnFiftyTicketsNeverOversell() throws Exception {
        UUID eventId = jdbc.sql("INSERT INTO events (name, total_capacity, available) VALUES (:n, 50, 50) RETURNING id")
                .param("n", "Race " + UUID.randomUUID()).query(UUID.class).single();
        int n = 200;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String key = "race-" + UUID.randomUUID();
            futures.add(pool.submit(() -> {
                var headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set("Idempotency-Key", key);
                ready.countDown();
                go.await();
                return rest.exchange("/events/" + eventId + "/reservations", HttpMethod.POST,
                        new HttpEntity<>("{\"quantity\":1}", headers), JsonNode.class).getStatusCode().value();
            }));
        }
        ready.await();
        go.countDown();
        int created = 0;
        int conflicts = 0;
        List<Integer> others = new ArrayList<>();
        for (var f : futures) {
            int status = f.get();
            if (status == 201) {
                created++;
            } else if (status == 409) {
                conflicts++;
            } else {
                others.add(status);
            }
        }
        pool.shutdown();

        assertThat(others).as("unexpected statuses (e.g. 503)").isEmpty();
        assertThat(created).isEqualTo(50);
        assertThat(conflicts).isEqualTo(150);
        assertThat(StockInvariant.available(jdbc, eventId)).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reservations WHERE event_id = :id").param("id", eventId)
                .query(Integer.class).single()).isEqualTo(50);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reservation_history WHERE event_id = :id").param("id", eventId)
                .query(Integer.class).single()).isEqualTo(50);
        StockInvariant.assertHolds(jdbc, eventId);
    }
}
