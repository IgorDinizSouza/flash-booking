package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractApiTest;

/** Actuator (health/info/metrics, nada alem disso) e contrato OpenAPI/Swagger (OBS-09..13, SEC-12). */
class ActuatorOpenApiTest extends AbstractApiTest {

    private JsonNode apiDocs() {
        var res = rest.getForEntity("/v3/api-docs", JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return res.getBody();
    }

    private static Set<String> names(JsonNode node) {
        Set<String> out = new TreeSet<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    // ------------------------------------------------------------------ Actuator

    @Test
    @DisplayName("OBS-09 /actuator/health responde 200 com status UP (banco no ar)")
    void healthIsUp() {
        var res = rest.getForEntity("/actuator/health", JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("OBS-10 o indice do Actuator expoe somente health, info e metrics")
    void actuatorIndexListsOnlyHealthInfoMetrics() {
        var res = rest.getForEntity("/actuator", JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Set<String> links = names(res.getBody().get("_links"));
        links.remove("self");
        links.remove("health-path");
        links.remove("metrics-requiredMetricName");
        assertThat(links).containsExactlyInAnyOrder("health", "info", "metrics");
    }

    @ParameterizedTest(name = "OBS-10/SEC-12 GET /actuator/{0} nao exposto")
    @ValueSource(strings = { "env", "beans", "heapdump", "loggers", "threaddump", "configprops", "mappings",
            "conditions", "caches", "scheduledtasks", "flyway", "prometheus", "httpexchanges", "auditevents",
            "sessions", "startup", "shutdown", "logfile", "env/spring.datasource.password" })
    void sensitiveActuatorEndpointsAreNotExposed(String endpoint) {
        var res = rest.getForEntity("/actuator/" + endpoint, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody() == null ? "" : res.getBody()).doesNotContain("jdbc:postgresql", "POSTGRES_PASSWORD");
    }

    @Test
    @DisplayName("SEC-12 POST /actuator/shutdown nao existe")
    void shutdownViaPostIsNotAvailable() {
        var res = rest.exchange("/actuator/shutdown", HttpMethod.POST, null, String.class);

        assertThat(res.getStatusCode().is4xxClientError()).isTrue();
        assertThat(res.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.METHOD_NOT_ALLOWED);
        // a aplicacao segue de pe
        assertThat(rest.getForEntity("/actuator/health", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("OBS-11 /actuator/info e /actuator/metrics respondem 200; reservations.created tem COUNT >= 1")
    void infoAndMetricsAreExposed() {
        assertThat(postReservation(newEvent(3), newKey(), 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(rest.getForEntity("/actuator/info", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var index = rest.getForEntity("/actuator/metrics", JsonNode.class);
        assertThat(index.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> metricNames = new ArrayList<>();
        index.getBody().get("names").forEach(n -> metricNames.add(n.asText()));
        assertThat(metricNames).contains("reservations.created");

        var created = rest.getForEntity("/actuator/metrics/reservations.created", JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().get("name").asText()).isEqualTo("reservations.created");
        JsonNode count = created.getBody().get("measurements").get(0);
        assertThat(count.get("statistic").asText()).isEqualTo("COUNT");
        assertThat(count.get("value").asDouble()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("OBS-11 /actuator/metrics/reservations.rejected aceita a tag reason")
    void rejectedMetricHasReasonTag() {
        UUID eventId = newEvent(1);
        postReservation(eventId, newKey(), 1);
        assertThat(postReservation(eventId, newKey(), 1).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var res = rest.getForEntity("/actuator/metrics/reservations.rejected?tag=reason:insufficient_capacity",
                JsonNode.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().get("measurements").get(0).get("value").asDouble()).isGreaterThanOrEqualTo(1.0);
    }

    // ------------------------------------------------------------------ Swagger UI

    @Test
    @DisplayName("OBS-12 Swagger UI responde (redirect de /swagger-ui.html e index 200 text/html)")
    void swaggerUiIsServed() {
        var headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_HTML));

        var index = rest.exchange("/swagger-ui/index.html", HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), String.class);
        assertThat(index.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(index.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(index.getBody()).contains("swagger-ui");

        var entry = rest.exchange("/swagger-ui.html", HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), String.class);
        if (entry.getStatusCode().is3xxRedirection()) {
            assertThat(entry.getHeaders().getLocation().toString()).endsWith("/swagger-ui/index.html");
        } else {
            assertThat(entry.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(entry.getBody()).contains("swagger-ui");
        }
    }

    // ------------------------------------------------------------------ OpenAPI

    @Test
    @DisplayName("OBS-13 /v3/api-docs lista exatamente as 5 operacoes do contrato")
    void apiDocsListExactlyTheFiveOperations() {
        JsonNode paths = apiDocs().get("paths");

        assertThat(names(paths)).containsExactlyInAnyOrder("/events", "/events/{id}", "/events/{id}/reservations",
                "/reservations/{id}");
        assertThat(names(paths.get("/events"))).containsExactly("post");
        assertThat(names(paths.get("/events/{id}"))).containsExactly("get");
        assertThat(names(paths.get("/events/{id}/reservations"))).containsExactly("post");
        assertThat(names(paths.get("/reservations/{id}"))).containsExactlyInAnyOrder("delete", "get");
    }

    @Test
    @DisplayName("OBS-13 codigos de resposta documentados por operacao")
    void apiDocsDocumentTheResponseCodes() {
        JsonNode paths = apiDocs().get("paths");

        assertThat(names(paths.get("/events").get("post").get("responses"))).contains("201", "400");
        assertThat(names(paths.get("/events/{id}").get("get").get("responses"))).contains("200", "400", "404");
        assertThat(names(paths.get("/events/{id}/reservations").get("post").get("responses")))
                .contains("201", "400", "404", "409", "503");
        assertThat(names(paths.get("/reservations/{id}").get("get").get("responses"))).contains("200", "400", "404");
        assertThat(names(paths.get("/reservations/{id}").get("delete").get("responses")))
                .contains("204", "400", "404", "409");
    }

    @Test
    @DisplayName("OBS-13 Idempotency-Key e parametro de header obrigatorio do POST de reserva")
    void idempotencyKeyIsARequiredHeaderParameter() {
        JsonNode post = apiDocs().get("paths").get("/events/{id}/reservations").get("post");

        JsonNode key = null;
        for (JsonNode p : post.get("parameters")) {
            if ("Idempotency-Key".equals(p.get("name").asText())) {
                key = p;
            }
        }
        assertThat(key).as("parametro Idempotency-Key").isNotNull();
        assertThat(key.get("in").asText()).isEqualTo("header");
        assertThat(key.get("required").asBoolean()).isTrue();
        assertThat(post.get("responses").get("201").get("headers").has("Idempotent-Replayed")).isTrue();
        assertThat(post.get("responses").get("201").get("headers").has("Location")).isTrue();
    }

    @Test
    @DisplayName("OBS-13 schemas do OpenAPI coincidem com os campos reais das respostas")
    void schemasMatchTheRealPayloads() {
        JsonNode schemas = apiDocs().get("components").get("schemas");
        UUID eventId = newEvent(5);
        ResponseEntity<JsonNode> event = postEvent("{\"name\":\"Schema " + UUID.randomUUID() + "\",\"capacity\":5}");
        ResponseEntity<JsonNode> reservation = postReservation(eventId, newKey(), 1);

        assertThat(names(schemas.get("CreateEventRequest").get("properties"))).containsExactly("capacity", "name");
        assertThat(names(schemas.get("CreateReservationRequest").get("properties"))).containsExactly("quantity");
        assertThat(names(schemas.get("EventResponse").get("properties"))).isEqualTo(names(event.getBody()));
        assertThat(names(schemas.get("ReservationResponse").get("properties")))
                .isEqualTo(names(reservation.getBody()));
        List<String> statuses = new ArrayList<>();
        schemas.get("ReservationResponse").get("properties").get("status").get("enum")
                .forEach(n -> statuses.add(n.asText()));
        assertThat(statuses).containsExactlyInAnyOrder("PENDING", "CANCELLED", "EXPIRED");
    }

    @Test
    @DisplayName("OBS-13 respostas de erro documentadas como application/problem+json com exemplo")
    void errorResponsesAreDocumentedAsProblemJson() {
        JsonNode responses = apiDocs().get("paths").get("/events/{id}/reservations").get("post").get("responses");

        for (String code : List.of("400", "404", "409")) {
            JsonNode content = responses.get(code).get("content");
            assertThat(content.has("application/problem+json")).as(code).isTrue();
            JsonNode problem = content.get("application/problem+json");
            assertThat(problem.has("examples") || problem.has("example")).as(code).isTrue();
        }
    }
}
