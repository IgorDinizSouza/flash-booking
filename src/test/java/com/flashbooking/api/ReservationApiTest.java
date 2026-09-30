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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

class ReservationApiTest extends AbstractIntegrationTest {

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

    private ResponseEntity<JsonNode> reserve(UUID eventId, String key, String json) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.exchange("/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>(json, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> reserve(UUID eventId, String key, int quantity) {
        return reserve(eventId, key, "{\"quantity\":" + quantity + "}");
    }

    private void assertError(ResponseEntity<JsonNode> res, HttpStatus status, String code) {
        assertThat(res.getStatusCode()).isEqualTo(status);
        assertThat(res.getBody().get("code").asText()).isEqualTo(code);
        assertThat(res.getBody().get("correlationId").asText()).isNotBlank();
    }

    private int count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }

    @Test
    void createsReservationAndDecrementsStockWithHistory() {
        UUID eventId = newEvent(10);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "k-" + UUID.randomUUID());
        headers.set("X-Correlation-Id", "corr-happy-path");
        var res = rest.exchange("/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>("{\"quantity\":3}", headers), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        JsonNode body = res.getBody();
        String id = body.get("id").asText();
        assertThat(res.getHeaders().getLocation()).hasPath("/reservations/" + id);
        assertThat(body.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(body.get("quantity").asInt()).isEqualTo(3);
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("expiresAt").asText()).isNotBlank();
        assertThat(body.get("createdAt").asText()).isNotBlank();

        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(7);

        var history = jdbc.sql("SELECT action, previous_status, new_status, quantity, reason, correlation_id, "
                + "instance_id, event_id FROM reservation_history WHERE reservation_id = :id")
                .param("id", UUID.fromString(id))
                .query((rs, n) -> new String[] {rs.getString("action"), rs.getString("previous_status"),
                        rs.getString("new_status"), rs.getString("quantity"), rs.getString("reason"),
                        rs.getString("correlation_id"), rs.getString("instance_id"), rs.getString("event_id")})
                .list();
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsExactly("CREATED", null, "PENDING", "3", "CLIENT_REQUEST",
                "corr-happy-path", "local", eventId.toString());

        var get = rest.getForEntity("/reservations/" + id, JsonNode.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(get.getBody().get("expiresAt")).isEqualTo(body.get("expiresAt"));
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void replayReturnsSameResponseWithHeaderAndDoesNotWriteAgain() {
        UUID eventId = newEvent(10);
        String key = "k-" + UUID.randomUUID();
        var first = reserve(eventId, key, 2);
        var second = reserve(eventId, key, 2);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(8);
        assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", eventId)).isEqualTo(1);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void concurrentRequestsWithSameKeyCreateOneReservation() throws Exception {
        UUID eventId = newEvent(10);
        String key = "k-" + UUID.randomUUID();
        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return reserve(eventId, key, 2);
            }));
        }
        ready.await();
        go.countDown();
        List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
        for (var f : futures) {
            responses.add(f.get());
        }
        pool.shutdown();

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(responses.stream().map(r -> r.getBody().get("id").asText()).distinct()).hasSize(1);
        assertThat(responses.stream().map(ResponseEntity::getBody).distinct()).hasSize(1);
        long replays = responses.stream().filter(r -> "true".equals(r.getHeaders().getFirst("Idempotent-Replayed")))
                .count();
        assertThat(replays).isEqualTo(n - 1);

        assertThat(count("SELECT COUNT(*) FROM reservations WHERE event_id = ?", eventId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", eventId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?", key)).isEqualTo(1);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(8);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void sameKeyWithDifferentQuantityConflicts() {
        UUID eventId = newEvent(10);
        String key = "k-" + UUID.randomUUID();
        assertThat(reserve(eventId, key, 2).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertError(reserve(eventId, key, 3), HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT");

        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(8);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void sameKeyOnDifferentEventConflicts() {
        UUID eventA = newEvent(10);
        UUID eventB = newEvent(10);
        String key = "k-" + UUID.randomUUID();
        assertThat(reserve(eventA, key, 2).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertError(reserve(eventB, key, 2), HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT");

        assertThat(StockInvariant.available(jdbc, eventB)).isEqualTo(10);
        StockInvariant.assertHolds(jdbc, eventA);
        StockInvariant.assertHolds(jdbc, eventB);
    }

    @Test
    void insufficientCapacityDoesNotRetainKey() {
        UUID eventId = newEvent(2);
        assertThat(reserve(eventId, "k-" + UUID.randomUUID(), 2).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String key = "k-" + UUID.randomUUID();
        assertError(reserve(eventId, key, 1), HttpStatus.CONFLICT, "INSUFFICIENT_CAPACITY");
        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?", key)).isZero();
        assertThat(count("SELECT COUNT(*) FROM reservations WHERE event_id = ?", eventId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", eventId)).isEqualTo(1);
        StockInvariant.assertHolds(jdbc, eventId);

        // devolve estoque diretamente por SQL e refaz com a MESMA chave
        jdbc.sql("UPDATE events SET available = available + 1 WHERE id = :id").param("id", eventId).update();
        jdbc.sql("UPDATE reservations SET quantity = 1 WHERE event_id = :id").param("id", eventId).update();

        var retry = reserve(eventId, key, 1);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(StockInvariant.available(jdbc, eventId)).isZero();
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void unknownEventReturns404AndLeavesNoResidue() {
        UUID unknown = UUID.randomUUID();
        String key = "k-" + UUID.randomUUID();

        assertError(reserve(unknown, key, 1), HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");

        assertThat(count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?", key)).isZero();
        assertThat(count("SELECT COUNT(*) FROM reservations WHERE event_id = ?", unknown)).isZero();
        assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", unknown)).isZero();
    }

    @Test
    void missingIdempotencyKeyReturns400() {
        assertError(reserve(newEvent(5), null, 1), HttpStatus.BAD_REQUEST, "MISSING_IDEMPOTENCY_KEY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void blankIdempotencyKeyReturns400(String key) {
        assertError(reserve(newEvent(5), key, 1), HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
    }

    @Test
    void tooLongIdempotencyKeyReturns400() {
        UUID eventId = newEvent(5);
        assertError(reserve(eventId, "k".repeat(151), 1), HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        // 150 e o limite aceito
        assertThat(reserve(eventId, "k".repeat(150), 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"quantity\":0}", "{\"quantity\":-1}", "{}", "{\"quantity\":null}",
            "{\"quantity\":11}"})
    void invalidQuantityReturns400(String json) {
        UUID eventId = newEvent(50);
        assertError(reserve(eventId, "k-" + UUID.randomUUID(), json), HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(50);
    }

    @Test
    void maxQuantityIsAccepted() {
        assertThat(reserve(newEvent(50), "k-" + UUID.randomUUID(), 10).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void malformedEventIdReturns400() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "k-" + UUID.randomUUID());
        var res = rest.exchange("/events/not-a-uuid/reservations", HttpMethod.POST,
                new HttpEntity<>("{\"quantity\":1}", headers), JsonNode.class);
        assertError(res, HttpStatus.BAD_REQUEST, "INVALID_ID_FORMAT");
    }

    @Test
    void malformedJsonReturns400() {
        assertError(reserve(newEvent(5), "k-" + UUID.randomUUID(), "{\"quantity\": "), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
    }

    @Test
    void getReservationWithMalformedIdReturns400() {
        assertError(rest.getForEntity("/reservations/xyz", JsonNode.class), HttpStatus.BAD_REQUEST,
                "INVALID_ID_FORMAT");
    }

    @Test
    void getUnknownReservationReturns404() {
        assertError(rest.getForEntity("/reservations/" + UUID.randomUUID(), JsonNode.class), HttpStatus.NOT_FOUND,
                "RESERVATION_NOT_FOUND");
    }

    @Test
    void getReportsExpiredForOverduePendingWithoutReturningStock() {
        UUID eventId = newEvent(10);
        var created = reserve(eventId, "k-" + UUID.randomUUID(), 4);
        String id = created.getBody().get("id").asText();
        assertThat(rest.getForEntity("/reservations/" + id, JsonNode.class).getBody().get("status").asText())
                .isEqualTo("PENDING");

        jdbc.sql("UPDATE reservations SET expires_at = NOW() - INTERVAL '1 minute' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();

        var get = rest.getForEntity("/reservations/" + id, JsonNode.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getBody().get("status").asText()).isEqualTo("EXPIRED");
        // sem efeito colateral: estado fisico e estoque intactos
        assertThat(jdbc.sql("SELECT status FROM reservations WHERE id = :id").param("id", UUID.fromString(id))
                .query(String.class).single()).isEqualTo("PENDING");
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(6);
        StockInvariant.assertHolds(jdbc, eventId);
    }
}
