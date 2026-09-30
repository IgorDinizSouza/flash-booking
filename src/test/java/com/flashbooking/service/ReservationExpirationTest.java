package com.flashbooking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractReservationTest;
import com.flashbooking.support.StockInvariant;

/** Expiracao (PLANO.md 5.4): caminho feliz, lote vazio e I16 completo do historico. */
class ReservationExpirationTest extends AbstractReservationTest {

    @Autowired
    IReservationExpirationService expiration;

    private record HistoryRow(String action, String previous, String next, String reason, String correlationId,
            String instanceId, int quantity) {
    }

    private List<HistoryRow> history(UUID reservationId, String action) {
        return jdbc.sql("SELECT action, previous_status, new_status, reason, correlation_id, instance_id, quantity "
                + "FROM reservation_history WHERE reservation_id = :id AND action = :a")
                .param("id", reservationId).param("a", action)
                .query((rs, n) -> new HistoryRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7)))
                .list();
    }

    @Test
    void expiresOnlyDueReservationsReturnsStockAndWritesHistory() {
        UUID eventId = newEvent(20);
        UUID a = reserveOk(eventId, 3);
        UUID b = reserveOk(eventId, 2);
        UUID fresh = reserveOk(eventId, 4);
        expireInPast(a);
        expireInPast(b);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(11);

        // I12: antes do job o GET ja mostra EXPIRED, mas o estoque ainda nao voltou
        assertThat(get(a).getBody().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(physicalStatus(a)).isEqualTo("PENDING");

        assertThat(expiration.expireBatch()).isGreaterThanOrEqualTo(2);

        assertThat(physicalStatus(a)).isEqualTo("EXPIRED");
        assertThat(physicalStatus(b)).isEqualTo("EXPIRED");
        assertThat(physicalStatus(fresh)).isEqualTo("PENDING");
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(16);
        assertThat(get(a).getBody().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(get(fresh).getBody().get("status").asText()).isEqualTo("PENDING");

        var rowA = history(a, "EXPIRED");
        assertThat(rowA).hasSize(1);
        assertThat(rowA.get(0)).satisfies(r -> {
            assertThat(r.previous()).isEqualTo("PENDING");
            assertThat(r.next()).isEqualTo("EXPIRED");
            assertThat(r.reason()).isEqualTo("TTL_EXPIRED");
            assertThat(r.correlationId()).startsWith("job-");
            assertThat(r.instanceId()).isEqualTo("local");
            assertThat(r.quantity()).isEqualTo(3);
        });
        assertThat(history(b, "EXPIRED")).hasSize(1);
        assertThat(historyCount(fresh, "EXPIRED")).isZero();
        assertThat(historyCount(a, "CREATED")).isEqualTo(1);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void expireBatchWithNothingDueReturnsZeroAndChangesNothing() {
        expiration.expireAll(1000); // limpa sobras de outros testes do mesmo banco
        UUID eventId = newEvent(10);
        UUID id = reserveOk(eventId, 3);

        assertThat(expiration.expireBatch()).isZero();

        assertThat(physicalStatus(id)).isEqualTo("PENDING");
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(7);
        assertThat(historyCountForEvent(eventId, "EXPIRED")).isZero();
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void expireAllDrainsMultipleBatchesUpToIterationLimit() {
        UUID eventId = newEvent(500);
        seedExpired(eventId, 250); // batch-size padrao = 100 -> 3 lotes
        expiration.expireAll(1000); // drena sobras de outros testes

        assertThat(countByStatus(eventId, "PENDING")).isZero();
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(500);
        assertThat(historyCountForEvent(eventId, "EXPIRED")).isEqualTo(250);
        StockInvariant.assertHolds(jdbc, eventId);

        UUID limited = newEvent(500);
        seedExpired(limited, 250);
        assertThat(expiration.expireAll(1)).isEqualTo(100); // uma iteracao = um lote cheio
        expiration.expireAll(1000);
        assertThat(countByStatus(limited, "PENDING")).isZero();
        StockInvariant.assertHolds(jdbc, limited);
    }

    @Test
    void historyHasExactlyOneRowPerEffectiveTransition() {
        UUID eventId = newEvent(3);
        String keyA = "k-" + UUID.randomUUID();
        UUID a = UUID.fromString(post(eventId, keyA, 1).get("id").asText());
        post(eventId, keyA, 1); // replay de idempotencia: sem linha nova

        assertThat(delete(a).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(a).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT); // DELETE repetido: no-op

        UUID b = reserveOk(eventId, 1);
        expireInPast(b);
        expiration.expireBatch();
        expiration.expireBatch(); // repeticao do job: nada a fazer para este evento
        assertThat(delete(b).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // EXPIRED: 409, sem linha

        assertThat(reserve(eventId, 5).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // rejeitada: rollback

        assertThat(historyCount(a, "CREATED")).isEqualTo(1);
        assertThat(historyCount(a, "CANCELLED")).isEqualTo(1);
        assertThat(historyCount(a, "EXPIRED")).isZero();
        assertThat(historyCount(b, "CREATED")).isEqualTo(1);
        assertThat(historyCount(b, "EXPIRED")).isEqualTo(1);
        assertThat(historyCount(b, "CANCELLED")).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reservation_history WHERE event_id = :e AND correlation_id IS NULL")
                .param("e", eventId).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reservation_history WHERE event_id = :e")
                .param("e", eventId).query(Integer.class).single()).isEqualTo(4);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(3);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    private JsonNode post(UUID eventId, String key, int quantity) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        var res = rest.exchange("/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>("{\"quantity\":" + quantity + "}", headers), JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        return res.getBody();
    }
}
