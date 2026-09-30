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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractIntegrationTest;
import com.flashbooking.support.StockInvariant;

/** DELETE /reservations/{id}: cancelamento, historico, I9 (parcial), I10, I11, I16 (parcial). */
class ReservationCancelTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcClient jdbc;

    private UUID newEvent(int capacity) {
        return jdbc.sql("INSERT INTO events (name, total_capacity, available) VALUES (:n, :c, :c) RETURNING id")
                .param("n", "Evt " + UUID.randomUUID())
                .param("c", capacity)
                .query(UUID.class).single();
    }

    private ResponseEntity<JsonNode> reserve(UUID eventId, int quantity) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "k-" + UUID.randomUUID());
        return rest.exchange("/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>("{\"quantity\":" + quantity + "}", headers), JsonNode.class);
    }

    private UUID reserveOk(UUID eventId, int quantity) {
        var res = reserve(eventId, quantity);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(res.getBody().get("id").asText());
    }

    private ResponseEntity<JsonNode> delete(String id) {
        return rest.exchange("/reservations/" + id, HttpMethod.DELETE, null, JsonNode.class);
    }

    private ResponseEntity<JsonNode> delete(UUID id) {
        return delete(id.toString());
    }

    private void assertError(ResponseEntity<JsonNode> res, HttpStatus status, String code) {
        assertThat(res.getStatusCode()).isEqualTo(status);
        assertThat(res.getBody().get("code").asText()).isEqualTo(code);
        assertThat(res.getBody().get("correlationId").asText()).isNotBlank();
    }

    private String physicalStatus(UUID id) {
        return jdbc.sql("SELECT status FROM reservations WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    private int historyCount(UUID reservationId, String action) {
        return jdbc.sql("SELECT COUNT(*) FROM reservation_history WHERE reservation_id = :id AND action = :a")
                .param("id", reservationId).param("a", action).query(Integer.class).single();
    }

    private void expireInPast(UUID id) {
        // created_at <= expires_at e um CHECK provavel; move ambos para o passado
        jdbc.sql("UPDATE reservations SET created_at = NOW() - interval '2 hours', "
                + "expires_at = NOW() - interval '1 hour' WHERE id = :id").param("id", id).update();
    }

    @Test
    void cancelsPendingReturnsStockAndWritesHistory() {
        UUID eventId = newEvent(10);
        UUID id = reserveOk(eventId, 3);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(7);

        var headers = new HttpHeaders();
        headers.set("X-Correlation-Id", "corr-cancel");
        var res = rest.exchange("/reservations/" + id, HttpMethod.DELETE, new HttpEntity<>(headers),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(res.getBody()).isNull();
        assertThat(physicalStatus(id)).isEqualTo("CANCELLED");
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(10);

        var get = rest.getForEntity("/reservations/" + id, JsonNode.class);
        assertThat(get.getBody().get("status").asText()).isEqualTo("CANCELLED");

        var history = jdbc.sql("SELECT action, previous_status, new_status, quantity, reason, correlation_id, "
                + "instance_id, event_id FROM reservation_history WHERE reservation_id = :id AND action = 'CANCELLED'")
                .param("id", id)
                .query((rs, n) -> new String[] {rs.getString("action"), rs.getString("previous_status"),
                        rs.getString("new_status"), rs.getString("quantity"), rs.getString("reason"),
                        rs.getString("correlation_id"), rs.getString("instance_id"), rs.getString("event_id")})
                .list();
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsExactly("CANCELLED", "PENDING", "CANCELLED", "3", "CLIENT_REQUEST",
                "corr-cancel", "local", eventId.toString());
        assertThat(historyCount(id, "CREATED")).isEqualTo(1);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void repeatedDeleteReturns204ButReturnsStockOnlyOnce() {
        UUID eventId = newEvent(10);
        UUID id = reserveOk(eventId, 4);

        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(10);
        assertThat(historyCount(id, "CANCELLED")).isEqualTo(1);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void twentyConcurrentDeletesOfSameReservationReturnStockOnce() throws Exception {
        UUID eventId = newEvent(10);
        UUID id = reserveOk(eventId, 5);

        List<Integer> statuses = runConcurrently(20, i -> delete(id).getStatusCode().value());

        assertThat(statuses).containsOnly(204);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(10);
        assertThat(historyCount(id, "CANCELLED")).isEqualTo(1);
        assertThat(physicalStatus(id)).isEqualTo("CANCELLED");
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void deleteOfPendingAlreadyPastExpiryIs409AndChangesNothing() {
        UUID eventId = newEvent(10);
        UUID id = reserveOk(eventId, 2);
        expireInPast(id);

        assertError(delete(id), HttpStatus.CONFLICT, "INVALID_RESERVATION_STATE");

        assertThat(physicalStatus(id)).isEqualTo("PENDING");
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(8);
        assertThat(historyCount(id, "CANCELLED")).isZero();
        assertThat(historyCount(id, "CREATED")).isEqualTo(1);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void deleteOfExpiredReservationIs409() {
        UUID eventId = newEvent(10);
        UUID id = reserveOk(eventId, 2);
        jdbc.sql("UPDATE reservations SET status = 'EXPIRED' WHERE id = :id").param("id", id).update();
        // o job (fase 5) devolveria o estoque; aqui so validamos a resposta, entao alinha a invariante
        jdbc.sql("UPDATE events SET available = available + 2 WHERE id = :id").param("id", eventId).update();

        assertError(delete(id), HttpStatus.CONFLICT, "INVALID_RESERVATION_STATE");

        assertThat(physicalStatus(id)).isEqualTo("EXPIRED");
        assertThat(historyCount(id, "CANCELLED")).isZero();
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void deleteOfUnknownReservationIs404() {
        assertError(delete(UUID.randomUUID()), HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND");
    }

    @Test
    void deleteWithMalformedIdIs400() {
        assertError(delete("not-a-uuid"), HttpStatus.BAD_REQUEST, "INVALID_ID_FORMAT");
    }

    @Test
    void cancellingFreesStockForNewReservation() {
        UUID eventId = newEvent(2);
        UUID id = reserveOk(eventId, 2);
        assertThat(reserve(eventId, 1).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(reserve(eventId, 2).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(StockInvariant.available(jdbc, eventId)).isZero();
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void thirtyParallelCancellationsOfDifferentReservationsKeepInvariant() throws Exception {
        UUID eventId = newEvent(30);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ids.add(reserveOk(eventId, 1));
        }
        assertThat(StockInvariant.available(jdbc, eventId)).isZero();

        List<Integer> statuses = runConcurrently(30, i -> delete(ids.get(i)).getStatusCode().value());

        assertThat(statuses).containsOnly(204);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(30);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reservation_history WHERE event_id = :id AND action = 'CANCELLED'")
                .param("id", eventId).query(Integer.class).single()).isEqualTo(30);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reservations WHERE event_id = :id AND status = 'CANCELLED'")
                .param("id", eventId).query(Integer.class).single()).isEqualTo(30);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    private interface Call {
        int run(int index);
    }

    private static List<Integer> runConcurrently(int n, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.run(index);
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (var f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }
}
