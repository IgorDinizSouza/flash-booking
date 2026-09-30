package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractReservationTest;
import com.flashbooking.support.DbLock;
import com.flashbooking.support.StockInvariant;

import io.micrometer.core.instrument.MeterRegistry;

/** I14: lock timeout forcado (transacao concorrente segurando a linha) vira 503 DATABASE_BUSY + Retry-After. */
@TestPropertySource(properties = "booking.db.lock-timeout=300ms")
class DatabaseBusyTest extends AbstractReservationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    MeterRegistry meters;

    private ResponseEntity<JsonNode> post(UUID eventId, String key, int quantity) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return rest.exchange("/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>("{\"quantity\":" + quantity + "}", headers), JsonNode.class);
    }

    private double busyCount() {
        return meters.counter("reservations.rejected", "reason", "db_busy").count();
    }

    static void assertBusy(ResponseEntity<JsonNode> res) {
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        JsonNode body = res.getBody();
        assertThat(body.get("code").asText()).isEqualTo("DATABASE_BUSY");
        assertThat(body.get("correlationId").asText()).isNotBlank();
        assertThat(body.get("status").asInt()).isEqualTo(503);
        String text = body.toString().toLowerCase();
        assertThat(text).doesNotContain("postgresql", "sql", "lock timeout", "stacktrace", "exception", "at com.",
                "at org.", "trace");
    }

    private int keyCount(String key) {
        return jdbc.sql("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = :k").param("k", key)
                .query(Integer.class).single();
    }

    private int reservationCount(UUID eventId) {
        return jdbc.sql("SELECT COUNT(*) FROM reservations WHERE event_id = :e").param("e", eventId)
                .query(Integer.class).single();
    }

    @Test
    void lockTimeoutOnPostReturns503LeavesNothingBehindAndRetryWithSameKeySucceeds() {
        UUID eventId = newEvent(10);
        String key = "busy-" + UUID.randomUUID();
        double before = busyCount();

        ResponseEntity<JsonNode> busy;
        try (DbLock lock = DbLock.lockEvent(dataSource, eventId)) {
            busy = post(eventId, key, 2);
        }
        assertBusy(busy);
        assertThat(busyCount()).isEqualTo(before + 1);

        assertThat(reservationCount(eventId)).isZero();
        assertThat(keyCount(key)).isZero();
        assertThat(historyCountForEvent(eventId, "CREATED")).isZero();
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(10);

        var retry = post(eventId, key, 2);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reservationCount(eventId)).isEqualTo(1);
        assertThat(historyCountForEvent(eventId, "CREATED")).isEqualTo(1);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(8);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void lockTimeoutOnDeleteReturns503ThenDeleteSucceedsAndReturnsStockOnce() {
        UUID eventId = newEvent(10);
        UUID id = reserveOk(eventId, 3);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(7);
        double before = busyCount();

        ResponseEntity<JsonNode> busy;
        try (DbLock lock = DbLock.lockReservation(dataSource, id)) {
            busy = delete(id);
        }
        assertBusy(busy);
        assertThat(busyCount()).isEqualTo(before + 1);
        assertThat(physicalStatus(id)).isEqualTo("PENDING");
        assertThat(historyCount(id, "CANCELLED")).isZero();
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(7);

        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(physicalStatus(id)).isEqualTo("CANCELLED");
        assertThat(historyCount(id, "CANCELLED")).isEqualTo(1);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(10);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void contendedIdempotencyKeyTimesOutAs503AndWorksAfterRelease() {
        UUID eventId = newEvent(10);
        String key = "contended-" + UUID.randomUUID();

        ResponseEntity<JsonNode> busy;
        try (DbLock lock = DbLock.insertIdempotencyKey(dataSource, key)) {
            busy = post(eventId, key, 1);
        }
        assertBusy(busy);
        assertThat(reservationCount(eventId)).isZero();
        assertThat(keyCount(key)).isZero();
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(10);

        var retry = post(eventId, key, 1);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reservationCount(eventId)).isEqualTo(1);
        StockInvariant.assertHolds(jdbc, eventId);
    }
}
