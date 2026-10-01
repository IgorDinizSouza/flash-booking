package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import com.flashbooking.support.AbstractApiTest;
import com.flashbooking.support.StockInvariant;

/** Lacunas de validacao de POST /events/{id}/reservations (RSV-08, 11, 14, 16, 18, 21..25 e QRY-07). */
class ReservationValidationTest extends AbstractApiTest {

    @ParameterizedTest(name = "RSV-08 corpo [{0}] => 400 MALFORMED_REQUEST")
    @ValueSource(strings = { "", "null" })
    @DisplayName("RSV-08 corpo vazio ou literal null")
    void emptyOrNullBodyIsMalformed(String body) {
        UUID eventId = newEvent(5);
        String key = newKey();

        var res = postReservation(eventId, key, body.isEmpty() ? null : body);

        assertProblem(res, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST");
        assertThat(available(eventId)).isEqualTo(5);
        assertThat(keyRows(key)).isZero();
        assertThat(reservationRows(eventId)).isZero();
    }

    @Test
    @DisplayName("RSV-11 campos extras sao ignorados e o eventId do path prevalece")
    void extraFieldsAreIgnoredAndPathEventWins() {
        UUID eventId = newEvent(10);
        UUID other = newEvent(10);

        var res = postReservation(eventId, newKey(),
                "{\"quantity\":2,\"foo\":\"bar\",\"eventId\":\"" + other + "\",\"status\":\"CANCELLED\"}");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(res.getBody().get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(res.getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(available(eventId)).isEqualTo(8);
        assertThat(available(other)).isEqualTo(10);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("RSV-14 Idempotency-Key com 1 caractere e aceita")
    void singleCharacterKeyIsAccepted() {
        UUID eventId = newEvent(5);
        // chave de 1 caractere e global: usa um caractere que nenhum outro teste usa e limpa a linha antes/depois
        String key = "Z";
        jdbc.sql("DELETE FROM idempotency_keys WHERE idempotency_key = :k").param("k", key).update();

        var res = postReservation(eventId, key, 1);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(available(eventId)).isEqualTo(4);
        assertThat(keyRows(key)).isEqualTo(1);
        jdbc.sql("DELETE FROM idempotency_keys WHERE idempotency_key = :k").param("k", key).update();
    }

    @ParameterizedTest(name = "RSV-16 chave [{0}]")
    @ValueSource(strings = { "a:b", "a/b", "a#b", "a%b", "a%2Fb", "a\"b", "a'b", "a;b", "a b c", "a=b&c=d", "a\\b",
            "k'; DROP TABLE idempotency_keys;--", "<script>x</script>", "a,b", "~!@$^*()+{}[]|?" })
    @DisplayName("RSV-16 chave com caracteres especiais ASCII e texto opaco e faz replay")
    void specialCharactersInKeyAreOpaque(String key) {
        UUID eventId = newEvent(10);
        String unique = key + "-" + UUID.randomUUID();

        var first = postReservation(eventId, unique, 1);
        var replay = postReservation(eventId, unique, 1);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(keyRows(unique)).as("chave gravada exatamente como enviada").isEqualTo(1);
        assertThat(available(eventId)).isEqualTo(9);
    }

    @Test
    @DisplayName("RSV-18 espacos nas bordas da Idempotency-Key sao removidos pelo servidor (mesma chave)")
    void keyWhitespaceAtTheEdgesIsTrimmedByTheServer() {
        UUID eventId = newEvent(10);
        String core = "trim-" + UUID.randomUUID();

        var first = postReservation(eventId, "  " + core + "  ", 1);
        var second = postReservation(eventId, core, 1);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("id").asText()).isEqualTo(first.getBody().get("id").asText());
        assertThat(keyRows(core)).isEqualTo(1);
        assertThat(reservationRows(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("RSV-21 reserva que esgota exatamente o estoque: 201, available 0, proxima 409")
    void reservationThatExhaustsStockExactly() {
        UUID eventId = newEvent(4);

        var res = postReservation(eventId, newKey(), 4);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(available(eventId)).isZero();
        assertProblem(postReservation(eventId, newKey(), 1), HttpStatus.CONFLICT, "INSUFFICIENT_CAPACITY");
        assertThat(available(eventId)).isZero();
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("RSV-22 quantity maior que o disponivel (ha estoque): 409 INSUFFICIENT_CAPACITY, nada muda")
    void quantityAboveAvailableIs409WithCode() {
        UUID eventId = newEvent(5);
        assertThat(postReservation(eventId, newKey(), 2).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String key = newKey();
        int historyBefore = count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", eventId);

        var res = postReservation(eventId, key, 4);

        assertProblem(res, HttpStatus.CONFLICT, "INSUFFICIENT_CAPACITY");
        assertThat(available(eventId)).isEqualTo(3);
        assertThat(reservationRows(eventId)).isEqualTo(1);
        assertThat(keyRows(key)).as("chave nao retida").isZero();
        assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", eventId))
                .isEqualTo(historyBefore);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("RSV-23 quantity maior que a capacidade total: 409 (nao 400), sem chave retida")
    void quantityAboveTotalCapacityIs409() {
        UUID eventId = newEvent(3);
        String key = newKey();

        var res = postReservation(eventId, key, 5);

        assertProblem(res, HttpStatus.CONFLICT, "INSUFFICIENT_CAPACITY");
        assertThat(available(eventId)).isEqualTo(3);
        assertThat(keyRows(key)).isZero();
        assertThat(reservationRows(eventId)).isZero();
    }

    @Test
    @DisplayName("RSV-19 evento inexistente com quantity valida: 404 EVENT_NOT_FOUND")
    void unknownEventIs404() {
        var res = postReservation(UUID.randomUUID(), newKey(), 1);

        assertProblem(res, HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND");
    }

    @Test
    @DisplayName("RSV-24 precedencia: chave invalida > quantity invalida > evento inexistente; path invalido primeiro")
    void validationPrecedence() {
        UUID unknown = UUID.randomUUID();
        UUID eventId = newEvent(5);

        // (a) chave em branco + quantity invalida => erro da chave
        assertProblem(postReservation(eventId, " ", "{\"quantity\":0}"), HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY");
        // (b) quantity invalida em evento inexistente => 400, nao 404 (validacao antes de consultar o evento)
        assertProblem(postReservation(unknown, newKey(), "{\"quantity\":0}"), HttpStatus.BAD_REQUEST,
                "INVALID_QUANTITY");
        // (c) UUID invalido no path + header ausente => erro do path
        assertProblem(postReservationTo("/events/not-a-uuid/reservations", null, "{\"quantity\":1}"),
                HttpStatus.BAD_REQUEST, "INVALID_ID_FORMAT");
        // (d) chave ausente + quantity invalida => header ausente (resolvido antes do corpo chegar ao service)
        assertProblem(postReservation(eventId, null, "{\"quantity\":0}"), HttpStatus.BAD_REQUEST,
                "MISSING_IDEMPOTENCY_KEY");
        assertThat(available(eventId)).isEqualTo(5);
    }

    @ParameterizedTest(name = "RSV-25/QRY-07 quantity {0}")
    @ValueSource(ints = { 1, 3 })
    @DisplayName("RSV-25/QRY-07 expiresAt = createdAt + TTL (10 min) em UTC, igual no GET")
    void timestampsAreCoherent(int quantity) {
        UUID eventId = newEvent(10);

        var created = postReservation(eventId, newKey(), quantity);
        var read = rest.exchange("/reservations/" + created.getBody().get("id").asText(), HttpMethod.GET, null,
                com.fasterxml.jackson.databind.JsonNode.class);

        String createdAtText = created.getBody().get("createdAt").asText();
        String expiresAtText = created.getBody().get("expiresAt").asText();
        assertThat(createdAtText).endsWith("Z");
        assertThat(expiresAtText).endsWith("Z");
        Instant createdAt = Instant.parse(createdAtText);
        Instant expiresAt = Instant.parse(expiresAtText);
        assertThat(Duration.between(createdAt, expiresAt)).isEqualTo(Duration.ofMinutes(10));
        assertThat(createdAt).isBeforeOrEqualTo(expiresAt);
        assertThat(Duration.between(createdAt, Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        assertThat(read.getBody().get("createdAt").asText()).isEqualTo(createdAtText);
        assertThat(read.getBody().get("expiresAt").asText()).isEqualTo(expiresAtText);
        assertThat(read.getBody().get("status").asText()).isEqualTo("PENDING");
    }
}
