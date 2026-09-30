package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractReservationTest;

/**
 * Robustez da API: erros HTTP do framework (404/405/415/406) como ProblemDetail, rejeicao de coercao numerica,
 * regras do nome do evento, Idempotency-Key replay e validacao do X-Correlation-Id.
 */
class ApiHardeningTest extends AbstractReservationTest {

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String contentType, String accept,
            String body) {
        var headers = new HttpHeaders();
        if (contentType != null) {
            headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        }
        if (accept != null) {
            headers.set(HttpHeaders.ACCEPT, accept);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> postEvent(String json) {
        return exchange(HttpMethod.POST, "/events", "application/json", null, json);
    }

    private ResponseEntity<JsonNode> postReservation(UUID eventId, String key, String json) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return rest.exchange("/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>(json, headers), JsonNode.class);
    }

    private void assertProblem(ResponseEntity<JsonNode> res, HttpStatus status, String code) {
        assertThat(res.getStatusCode()).isEqualTo(status);
        assertThat(res.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        JsonNode body = res.getBody();
        assertThat(body).as("ProblemDetail body").isNotNull();
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(status.value());
        assertThat(body.get("correlationId").asText()).isNotBlank();
        assertThat(res.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(body.get("correlationId").asText());
        String text = body.toString().toLowerCase();
        assertThat(text).doesNotContain("exception", "at org.", "at com.", "stacktrace");
    }

    // ---------------------------------------------------------------- D1: erros HTTP do framework

    @ParameterizedTest
    @ValueSource(strings = { "/nao-existe", "/reservations", "/events/abc/def/ghi", "/favicon.ico" })
    void unknownRouteIs404ProblemDetail(String path) {
        assertProblem(exchange(HttpMethod.GET, path, null, null, null), HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND");
    }

    @ParameterizedTest
    @CsvSource({
            "PUT,/events,POST",
            "GET,/events,POST",
            "DELETE,/events/00000000-0000-4000-8000-000000000001,GET",
            "POST,/reservations/00000000-0000-4000-8000-000000000001,GET",
            "GET,/events/00000000-0000-4000-8000-000000000001/reservations,POST" })
    void methodNotAllowedIs405WithAllowHeader(String method, String path, String allowContains) {
        var res = exchange(HttpMethod.valueOf(method), path, null, null, null);

        assertProblem(res, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        assertThat(res.getHeaders().getFirst(HttpHeaders.ALLOW)).contains(allowContains);
    }

    @ParameterizedTest
    @ValueSource(strings = { "text/plain", "application/xml" })
    void unsupportedMediaTypeIs415(String contentType) {
        assertProblem(exchange(HttpMethod.POST, "/events", contentType, null, "{\"name\":\"A\",\"capacity\":1}"),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unsupportedMediaTypeOnReservationWithKeyIs415() {
        UUID eventId = newEvent(5);
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "text/plain");
        headers.set("Idempotency-Key", "k-" + UUID.randomUUID());
        var res = rest.exchange("/events/" + eventId + "/reservations", HttpMethod.POST,
                new HttpEntity<>("{\"quantity\":1}", headers), JsonNode.class);

        assertProblem(res, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void notAcceptableIs406ProblemDetail() {
        var created = postEvent("{\"name\":\"Acc " + UUID.randomUUID() + "\",\"capacity\":3}").getBody();
        var res = exchange(HttpMethod.GET, "/events/" + created.get("id").asText(), null, "application/xml", null);

        assertProblem(res, HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE");
    }

    // ---------------------------------------------------------------- D2: coercao numerica

    @ParameterizedTest
    @ValueSource(strings = { "10.5", "10.0", "\"10\"", "\"abc\"", "true", "[]", "3000000000" })
    void capacityRejectsDecimalsAndText(String value) {
        assertProblem(postEvent("{\"name\":\"A\",\"capacity\":" + value + "}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = { "2.5", "2.0", "\"2\"", "\"abc\"", "true", "[]", "99999999999" })
    void quantityRejectsDecimalsAndText(String value) {
        UUID eventId = newEvent(20);
        var res = postReservation(eventId, "k-" + UUID.randomUUID(), "{\"quantity\":" + value + "}");

        assertProblem(res, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(jdbc.sql("SELECT available FROM events WHERE id = :id").param("id", eventId)
                .query(Integer.class).single()).isEqualTo(20);
    }

    @Test
    void integerNumbersStillWorkAndNullOrMissingKeepTheirCodes() {
        assertThat(postEvent("{\"name\":\"Ok " + UUID.randomUUID() + "\",\"capacity\":10}").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertProblem(postEvent("{\"name\":\"A\",\"capacity\":null}"), HttpStatus.BAD_REQUEST, "INVALID_CAPACITY");
        assertProblem(postEvent("{\"name\":\"A\"}"), HttpStatus.BAD_REQUEST, "INVALID_CAPACITY");

        UUID eventId = newEvent(20);
        assertThat(postReservation(eventId, "k-" + UUID.randomUUID(), "{\"quantity\":2}").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertProblem(postReservation(eventId, "k-" + UUID.randomUUID(), "{\"quantity\":null}"),
                HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
        assertProblem(postReservation(eventId, "k-" + UUID.randomUUID(), "{}"), HttpStatus.BAD_REQUEST,
                "INVALID_QUANTITY");
    }

    @Test
    void idempotencyHashStaysDeterministicForEquivalentIntegerBodies() {
        UUID eventId = newEvent(20);
        String key = "k-" + UUID.randomUUID();

        var first = postReservation(eventId, key, "{\"quantity\":2}");
        var second = postReservation(eventId, key, "{  \"quantity\" : 2 , \"x\" : 1 }");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody());
        // o "mesmo" request com decimal nao vira replay: e rejeitado
        assertProblem(postReservation(eventId, key, "{\"quantity\":2.0}"), HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST");
    }

    // ---------------------------------------------------------------- D3: replay congelado (decisao de projeto)

    @Test
    void replayAfterCancelReturnsFrozenOriginalResponse() {
        UUID eventId = newEvent(5);
        String key = "k-" + UUID.randomUUID();
        var first = postReservation(eventId, key, "{\"quantity\":2}");
        UUID id = UUID.fromString(first.getBody().get("id").asText());
        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var replay = postReservation(eventId, key, "{\"quantity\":2}");

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(get(id).getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reservations WHERE event_id = :e").param("e", eventId)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT available FROM events WHERE id = :e").param("e", eventId)
                .query(Integer.class).single()).isEqualTo(5);
    }

    @Test
    void replayAfterExpirationReturnsFrozenOriginalResponse() {
        UUID eventId = newEvent(5);
        String key = "k-" + UUID.randomUUID();
        var first = postReservation(eventId, key, "{\"quantity\":2}");
        UUID id = UUID.fromString(first.getBody().get("id").asText());
        expireInPast(id);

        var replay = postReservation(eventId, key, "{\"quantity\":2}");

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(get(id).getBody().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM reservations WHERE event_id = :e").param("e", eventId)
                .query(Integer.class).single()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- D5: nome do evento

    @ParameterizedTest
    @ValueSource(strings = { "a\\u0000b", "\\u0000", "a\\u0001b", "a\\nb", "a\\tb", "a\\u007fb", "a\\u0085b",
            "abc\\u0000", "\\ud800x" })
    void nameWithControlCharactersIsRejected(String escaped) {
        assertProblem(postEvent("{\"name\":\"" + escaped + "\",\"capacity\":1}"), HttpStatus.BAD_REQUEST,
                "INVALID_EVENT_NAME");
    }

    @Test
    void nameLengthIsCountedInCodePointsAfterTrim() {
        // 150 uteis + bordas: aceito (o que e gravado tem 150)
        String padded = "  " + "x".repeat(150) + "  ";
        var ok = postEvent("{\"name\":\"" + padded + "\",\"capacity\":1}");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody().get("name").asText()).isEqualTo("x".repeat(150));

        // 151 uteis: rejeitado
        assertProblem(postEvent("{\"name\":\"" + "x".repeat(151) + "\",\"capacity\":1}"), HttpStatus.BAD_REQUEST,
                "INVALID_EVENT_NAME");

        // 150 emojis (300 unidades UTF-16, 150 code points): aceito e gravado/lido igual
        String emojis = "😀".repeat(150);
        var emoji = postEvent("{\"name\":\"" + emojis + "\",\"capacity\":1}");
        assertThat(emoji.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var read = rest.getForEntity("/events/" + emoji.getBody().get("id").asText(), JsonNode.class);
        assertThat(read.getBody().get("name").asText()).isEqualTo(emojis);

        // 151 emojis: rejeitado
        assertProblem(postEvent("{\"name\":\"" + "😀".repeat(151) + "\",\"capacity\":1}"),
                HttpStatus.BAD_REQUEST, "INVALID_EVENT_NAME");
    }

    @Test
    void accentedNameRoundTripsAndIsTrimmed() {
        var res = postEvent("{\"name\":\"  Festival São João 🎉  \",\"capacity\":2}");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("name").asText()).isEqualTo("Festival São João 🎉");
    }

    // ---------------------------------------------------------------- D18: X-Correlation-Id

    @ParameterizedTest
    @ValueSource(strings = { "has space", "a\"b", "x;y", "<script>", "a/b", "ça-va", "a,b", "a:b" })
    void unsafeCorrelationIdIsReplacedByGeneratedOne(String sent) {
        var headers = new HttpHeaders();
        headers.set("X-Correlation-Id", sent);
        var res = rest.exchange("/events/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(headers),
                JsonNode.class);

        String echoed = res.getHeaders().getFirst("X-Correlation-Id");
        assertThat(echoed).isNotEqualTo(sent);
        assertThat(echoed).matches("[0-9a-f-]{36}");
        assertThat(res.getBody().get("correlationId").asText()).isEqualTo(echoed);
    }

    @ParameterizedTest
    @ValueSource(strings = { "abc-123_X.y", "A", "0123456789012345678901234567890123456789012345678901234567890123" })
    void safeCorrelationIdIsEchoed(String sent) {
        var headers = new HttpHeaders();
        headers.set("X-Correlation-Id", sent);
        var res = rest.exchange("/events/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(headers),
                JsonNode.class);

        assertThat(res.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(sent);
        assertThat(res.getBody().get("correlationId").asText()).isEqualTo(sent);
    }

    @Test
    void correlationIdOver64CharsIsReplaced() {
        String sent = "a".repeat(65);
        var headers = new HttpHeaders();
        headers.set("X-Correlation-Id", sent);
        var res = rest.exchange("/events/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(headers),
                JsonNode.class);

        assertThat(res.getHeaders().getFirst("X-Correlation-Id")).isNotEqualTo(sent).hasSize(36);
    }
}
