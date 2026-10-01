package com.flashbooking.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;

/** Helpers HTTP adicionais (POST de evento/reserva com corpo bruto, ProblemDetail, contagens SQL). */
public abstract class AbstractApiTest extends AbstractReservationTest {

    protected ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String contentType, String body) {
        var headers = new HttpHeaders();
        if (contentType != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    /** Envia o caminho sem recodificacao (TestRestTemplate recodificaria os %XX de um template String). */
    protected ResponseEntity<JsonNode> exchangeRawPath(HttpMethod method, String rawPath, HttpHeaders headers,
            String body) {
        return rest.exchange(URI.create(rest.getRootUri() + rawPath), method, new HttpEntity<>(body, headers),
                JsonNode.class);
    }

    protected ResponseEntity<String> exchangeRawPathText(HttpMethod method, String rawPath) {
        return rest.exchange(URI.create(rest.getRootUri() + rawPath), method, new HttpEntity<>(new HttpHeaders()),
                String.class);
    }

    protected ResponseEntity<JsonNode> postEvent(String json) {
        return exchange(HttpMethod.POST, "/events", "application/json", json);
    }

    protected ResponseEntity<JsonNode> postReservation(UUID eventId, String key, String json) {
        return postReservationTo("/events/" + eventId + "/reservations", key, json);
    }

    protected ResponseEntity<JsonNode> postReservationTo(String path, String key, String json) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.set("Idempotency-Key", key);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> postReservation(UUID eventId, String key, int quantity) {
        return postReservation(eventId, key, "{\"quantity\":" + quantity + "}");
    }

    protected static String newKey() {
        return "k-" + UUID.randomUUID();
    }

    protected void assertProblem(ResponseEntity<JsonNode> res, HttpStatus status, String code) {
        assertThat(res.getStatusCode()).isEqualTo(status);
        assertThat(res.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        JsonNode body = res.getBody();
        assertThat(body).as("ProblemDetail body").isNotNull();
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(status.value());
        assertThat(body.get("correlationId").asText()).isNotBlank();
        assertThat(res.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(body.get("correlationId").asText());
        assertThat(body.toString().toLowerCase()).doesNotContain("exception", "at org.", "at com.", "stacktrace");
    }

    protected int count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }

    protected int keyRows(String key) {
        return count("SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?", key);
    }

    protected int reservationRows(UUID eventId) {
        return count("SELECT COUNT(*) FROM reservations WHERE event_id = ?", eventId);
    }

    protected int available(UUID eventId) {
        return StockInvariant.available(jdbc, eventId);
    }
}
