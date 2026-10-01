package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractApiTest;

/** Contrato HTTP: campos do ProblemDetail (ERR-17), X-Instance-Id em toda resposta (ERR-27) e correlation id no historico (HST-11). */
class ApiContractTest extends AbstractApiTest {

    @Test
    @DisplayName("ERR-17 ProblemDetail real (404 e 409) tem type, title, status, detail, instance, code e correlationId")
    void problemDetailHasAllStandardFields() {
        UUID eventId = newEvent(1);
        UUID unknown = UUID.randomUUID();
        postReservation(eventId, newKey(), 1);

        var notFound = rest.getForEntity("/events/" + unknown, JsonNode.class);
        var conflict = postReservation(eventId, newKey(), 1);

        assertFields(notFound, 404, "Not Found", "EVENT_NOT_FOUND", "/events/" + unknown);
        assertFields(conflict, 409, "Conflict", "INSUFFICIENT_CAPACITY", "/events/" + eventId + "/reservations");
    }

    private void assertFields(ResponseEntity<JsonNode> res, int status, String title, String code, String instance) {
        JsonNode b = res.getBody();
        assertThat(b.get("type").asText()).isEqualTo("about:blank");
        assertThat(b.get("title").asText()).isEqualTo(title);
        assertThat(b.get("status").asInt()).isEqualTo(status);
        assertThat(b.get("detail").asText()).isNotBlank();
        assertThat(b.get("instance").asText()).isEqualTo(instance);
        assertThat(b.get("code").asText()).isEqualTo(code);
        assertThat(b.get("correlationId").asText()).isEqualTo(res.getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    @DisplayName("ERR-27 X-Instance-Id presente em respostas 201, 200, 204, 400, 404 e 409")
    void instanceIdHeaderIsPresentOnEveryResponse() {
        UUID soldOut = newEvent(1);
        postReservation(soldOut, newKey(), 1);
        UUID eventId = newEvent(3);
        var created = postReservation(eventId, newKey(), 1);
        UUID id = UUID.fromString(created.getBody().get("id").asText());

        var responses = java.util.List.of(
                created,
                rest.getForEntity("/events/" + eventId, JsonNode.class),
                rest.getForEntity("/reservations/" + id, JsonNode.class),
                delete(id),
                postReservation(soldOut, newKey(), 1),                      // 409
                postReservation(eventId, newKey(), 0),                      // 400
                rest.getForEntity("/events/" + UUID.randomUUID(), JsonNode.class), // 404
                rest.getForEntity("/nao-existe", JsonNode.class));          // 404 de rota

        assertThat(responses.stream().map(r -> r.getStatusCode().value()).toList())
                .containsExactly(201, 200, 200, 204, 409, 400, 404, 404);
        assertThat(responses).allSatisfy(r -> assertThat(r.getHeaders().getFirst("X-Instance-Id")).isEqualTo("local"));
    }

    @Test
    @DisplayName("HST-11 X-Correlation-Id de 64 caracteres e gravado inteiro no historico; 65 e substituido por UUID")
    void correlationIdLengthLimitOnHistory() {
        UUID eventId = newEvent(10);
        String id64 = "c".repeat(64);
        String id65 = "d".repeat(65);

        var ok = reserveWithCorrelation(eventId, id64);
        var replaced = reserveWithCorrelation(eventId, id65);

        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(id64);
        assertThat(jdbc.sql("SELECT length(correlation_id) FROM reservation_history WHERE reservation_id = :id")
                .param("id", UUID.fromString(ok.getBody().get("id").asText())).query(Integer.class).single())
                .isEqualTo(64);

        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String generated = replaced.getHeaders().getFirst("X-Correlation-Id");
        assertThat(generated).isNotEqualTo(id65).hasSize(36);
        assertThat(jdbc.sql("SELECT correlation_id FROM reservation_history WHERE reservation_id = :id")
                .param("id", UUID.fromString(replaced.getBody().get("id").asText())).query(String.class).single())
                .isEqualTo(generated);
        assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ? AND correlation_id IS NULL",
                eventId)).isZero();
    }

    private ResponseEntity<JsonNode> reserveWithCorrelation(UUID eventId, String correlationId) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", newKey());
        headers.set("X-Correlation-Id", correlationId);
        return rest.exchange("/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>("{\"quantity\":1}", headers), JsonNode.class);
    }
}
