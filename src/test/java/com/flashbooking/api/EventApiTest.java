package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractIntegrationTest;

class EventApiTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<JsonNode> postJson(String json) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/events", HttpMethod.POST, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> create(String name, int capacity) {
        return rest.postForEntity("/events", Map.of("name", name, "capacity", capacity), JsonNode.class);
    }

    private void assertError(ResponseEntity<JsonNode> res, HttpStatus status, String code) {
        assertThat(res.getStatusCode()).isEqualTo(status);
        assertThat(res.getBody().get("code").asText()).isEqualTo(code);
        assertThat(res.getBody().get("correlationId").asText()).isNotBlank();
        assertThat(res.getHeaders().getFirst("X-Correlation-Id"))
                .isEqualTo(res.getBody().get("correlationId").asText());
    }

    @Test
    void createsEvent() {
        String name = "Festival " + UUID.randomUUID();
        var res = create(name, 50);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = res.getBody();
        String id = body.get("id").asText();
        assertThat(res.getHeaders().getLocation()).hasPath("/events/" + id);
        assertThat(body.get("name").asText()).isEqualTo(name);
        assertThat(body.get("capacity").asInt()).isEqualTo(50);
        assertThat(body.get("available").asInt()).isEqualTo(50);
        assertThat(body.get("createdAt").asText()).isNotBlank();
    }

    @Test
    void getsCreatedEvent() {
        JsonNode created = create("Get " + UUID.randomUUID(), 7).getBody();

        var res = rest.getForEntity("/events/" + created.get("id").asText(), JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("id")).isEqualTo(created.get("id"));
        assertThat(res.getBody().get("name")).isEqualTo(created.get("name"));
        assertThat(res.getBody().get("capacity").asInt()).isEqualTo(7);
        assertThat(res.getBody().get("available").asInt()).isEqualTo(7);
    }

    @Test
    void unknownEventReturns404() {
        assertError(rest.getForEntity("/events/" + UUID.randomUUID(), JsonNode.class), HttpStatus.NOT_FOUND,
                "EVENT_NOT_FOUND");
    }

    @Test
    void malformedUuidReturns400() {
        assertError(rest.getForEntity("/events/not-a-uuid", JsonNode.class), HttpStatus.BAD_REQUEST,
                "INVALID_ID_FORMAT");
    }

    @Test
    void blankNameIsRejected() {
        assertError(postJson("{\"name\":\"   \",\"capacity\":10}"), HttpStatus.BAD_REQUEST, "INVALID_EVENT_NAME");
    }

    @Test
    void missingNameIsRejected() {
        assertError(postJson("{\"capacity\":10}"), HttpStatus.BAD_REQUEST, "INVALID_EVENT_NAME");
    }

    @Test
    void nullNameIsRejected() {
        assertError(postJson("{\"name\":null,\"capacity\":10}"), HttpStatus.BAD_REQUEST, "INVALID_EVENT_NAME");
    }

    @Test
    void tooLongNameIsRejected() {
        assertError(create("x".repeat(151), 10), HttpStatus.BAD_REQUEST, "INVALID_EVENT_NAME");
    }

    @Test
    void nameWith150CharsIsAccepted() {
        assertThat(create("x".repeat(150), 10).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, -1, 1_000_001 })
    void capacityOutOfRangeIsRejected(int capacity) {
        assertError(create("Cap " + UUID.randomUUID(), capacity), HttpStatus.BAD_REQUEST, "INVALID_CAPACITY");
    }

    @Test
    void capacityBoundsAreAccepted() {
        assertThat(create("Min " + UUID.randomUUID(), 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(create("Max " + UUID.randomUUID(), 1_000_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void missingCapacityIsRejected() {
        assertError(postJson("{\"name\":\"A\"}"), HttpStatus.BAD_REQUEST, "INVALID_CAPACITY");
        assertError(postJson("{\"name\":\"A\",\"capacity\":null}"), HttpStatus.BAD_REQUEST, "INVALID_CAPACITY");
    }

    @Test
    void malformedJsonIsRejected() {
        assertError(postJson("{\"name\":"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertError(postJson("{\"name\":\"A\",\"capacity\":\"abc\"}"), HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
    }

    @Test
    void echoesCorrelationIdAndExposesInstanceId() {
        String correlationId = "test-" + UUID.randomUUID();
        var headers = new HttpHeaders();
        headers.set("X-Correlation-Id", correlationId);

        var res = rest.exchange("/events/" + UUID.randomUUID(), HttpMethod.GET, new HttpEntity<>(headers),
                JsonNode.class);

        assertThat(res.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(correlationId);
        assertThat(res.getHeaders().getFirst("X-Instance-Id")).isNotBlank();
        assertThat(res.getBody().get("correlationId").asText()).isEqualTo(correlationId);
    }

    @Test
    void generatesCorrelationIdWhenAbsent() {
        var res = create("Corr " + UUID.randomUUID(), 5);

        assertThat(res.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }
}
