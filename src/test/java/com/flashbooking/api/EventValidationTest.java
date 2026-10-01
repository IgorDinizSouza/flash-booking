package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractApiTest;

/** Lacunas de validacao de POST /events (catalogo: EVT-08, 12, 18, 21, 22, 23, 24 e ERR-28). */
class EventValidationTest extends AbstractApiTest {

    @Test
    @DisplayName("EVT-08 name com 1 caractere e aceito e devolvido igual")
    void singleCharacterNameIsAccepted() {
        var res = postEvent("{\"name\":\"A\",\"capacity\":10}");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("name").asText()).isEqualTo("A");
        assertThat(res.getBody().get("available").asInt()).isEqualTo(10);
    }

    @Test
    @DisplayName("EVT-12 name vazio e rejeitado com INVALID_EVENT_NAME")
    void emptyNameIsRejected() {
        assertProblem(postEvent("{\"name\":\"\",\"capacity\":10}"), HttpStatus.BAD_REQUEST, "INVALID_EVENT_NAME");
    }

    @ParameterizedTest(name = "EVT-18 corpo [{0}] => 400 MALFORMED_REQUEST")
    @ValueSource(strings = { "", "null", "   " })
    @DisplayName("EVT-18 corpo vazio ou null")
    void emptyBodyIsMalformed(String body) {
        long before = count("SELECT COUNT(*) FROM events");

        var res = postEvent(body.isEmpty() ? null : body);

        assertProblem(res, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(count("SELECT COUNT(*) FROM events")).isEqualTo((int) before);
    }

    @Test
    @DisplayName("EVT-21 campos extras (id, available, createdAt) sao ignorados")
    void extraFieldsAreIgnored() {
        String sentId = UUID.randomUUID().toString();
        var res = postEvent("{\"name\":\"Mass " + UUID.randomUUID() + "\",\"capacity\":5,\"id\":\"" + sentId
                + "\",\"available\":999,\"totalCapacity\":999,\"createdAt\":\"2000-01-01T00:00:00Z\"}");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = res.getBody();
        assertThat(body.get("id").asText()).isNotEqualTo(sentId);
        assertThat(body.get("capacity").asInt()).isEqualTo(5);
        assertThat(body.get("available").asInt()).isEqualTo(5);
        assertThat(Instant.parse(body.get("createdAt").asText())).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(count("SELECT COUNT(*) FROM events WHERE id = ?::uuid", sentId)).isZero();
    }

    @Test
    @DisplayName("EVT-22 dois eventos com o mesmo nome recebem ids distintos")
    void sameNameTwiceCreatesTwoEvents() {
        String name = "Dup " + UUID.randomUUID();
        var a = postEvent("{\"name\":\"" + name + "\",\"capacity\":3}");
        var b = postEvent("{\"name\":\"" + name + "\",\"capacity\":3}");

        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(a.getBody().get("id").asText()).isNotEqualTo(b.getBody().get("id").asText());
        assertThat(count("SELECT COUNT(*) FROM events WHERE name = ?", name)).isEqualTo(2);
    }

    @Test
    @DisplayName("EVT-23 50 criacoes concorrentes geram 50 ids distintos")
    void concurrentCreationsProduceDistinctIds() throws Exception {
        String prefix = "Conc " + UUID.randomUUID() + " ";
        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String json = "{\"name\":\"" + prefix + i + "\",\"capacity\":7}";
            tasks.add(() -> postEvent(json));
        }

        var results = runConcurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(results.stream().map(r -> r.getBody().get("id").asText()).distinct()).hasSize(50);
        assertThat(count("SELECT COUNT(*) FROM events WHERE name LIKE ?", prefix + "%")).isEqualTo(50);
    }

    @Test
    @DisplayName("EVT-24 createdAt em ISO-8601 UTC, proximo do horario atual")
    void createdAtIsIsoUtcCloseToNow() {
        var res = postEvent("{\"name\":\"Time " + UUID.randomUUID() + "\",\"capacity\":1}");

        String createdAt = res.getBody().get("createdAt").asText();
        assertThat(createdAt).endsWith("Z");
        assertThat(Duration.between(Instant.parse(createdAt), Instant.now()).abs())
                .isLessThan(Duration.ofMinutes(1));
        var read = rest.exchange("/events/" + res.getBody().get("id").asText(), HttpMethod.GET, null, JsonNode.class);
        assertThat(read.getBody().get("createdAt").asText()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("ERR-28 dois campos invalidos: resposta sempre 400 com code estavel (ordem alfabetica do campo: capacity vence name)")
    void twoInvalidFieldsGiveAStableCode() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            var res = postEvent("{\"name\":\"\",\"capacity\":0}");
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            codes.add(res.getBody().get("code").asText());
        }

        assertThat(codes).allSatisfy(c -> assertThat(c).isIn("INVALID_EVENT_NAME", "INVALID_CAPACITY"));
        assertThat(codes.stream().distinct()).as("code estavel entre chamadas iguais (D12)").hasSize(1);
    }

    @Test
    @DisplayName("EVT-30 cache desabilitado (perfil de teste): GET le direto do banco, sem defasagem")
    void disabledCacheReadsStraightFromTheDatabase() {
        UUID eventId = newEvent(20);
        String path = "/events/" + eventId;
        assertThat(rest.getForEntity(path, JsonNode.class).getBody().get("available").asInt()).isEqualTo(20);

        jdbc.sql("UPDATE events SET available = 7 WHERE id = :id").param("id", eventId).update();

        assertThat(rest.getForEntity(path, JsonNode.class).getBody().get("available").asInt()).isEqualTo(7);
        jdbc.sql("UPDATE events SET available = 11 WHERE id = :id").param("id", eventId).update();
        assertThat(rest.getForEntity(path, JsonNode.class).getBody().get("available").asInt()).isEqualTo(11);
    }
}
