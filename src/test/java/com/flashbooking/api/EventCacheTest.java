package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractIntegrationTest;

@TestPropertySource(properties = {
        "booking.availability-cache.enabled=true",
        "booking.availability-cache.ttl=3s" })
class EventCacheTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcClient jdbc;

    private int availableOf(String id) {
        return rest.getForObject("/events/" + id, JsonNode.class).get("available").asInt();
    }

    @Test
    void servesCachedValueUntilTtlExpiresThenConverges() {
        JsonNode created = rest.postForObject("/events",
                Map.of("name", "Cache " + UUID.randomUUID(), "capacity", 100), JsonNode.class);
        String id = created.get("id").asText();

        assertThat(availableOf(id)).isEqualTo(100); // popula o cache

        jdbc.sql("UPDATE events SET available = 40 WHERE id = :id").param("id", UUID.fromString(id)).update();

        // Leitura imediata (dentro do TTL de 3s): ainda o valor em cache.
        assertThat(availableOf(id)).isEqualTo(100);

        // Apos o TTL, converge para o valor do banco.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(availableOf(id)).isEqualTo(40));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("EVT-29 GET apos reserva pela mesma instancia: valor antigo dentro do TTL (sem evict), converge depois")
    void availabilityAfterReservationViaApiIsStaleWithinTtlThenConverges() {
        JsonNode created = rest.postForObject("/events",
                Map.of("name", "CacheRes " + UUID.randomUUID(), "capacity", 10), JsonNode.class);
        String id = created.get("id").asText();
        assertThat(availableOf(id)).isEqualTo(10); // popula o cache

        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "k-" + UUID.randomUUID());
        var res = rest.exchange("/events/" + id + "/reservations", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>("{\"quantity\":4}", headers), JsonNode.class);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT available FROM events WHERE id = :id").param("id", UUID.fromString(id))
                .query(Integer.class).single()).isEqualTo(6);

        assertThat(availableOf(id)).as("dentro do TTL o cache nao e invalidado pela reserva").isEqualTo(10);
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertThat(availableOf(id)).isEqualTo(6));
    }
}
