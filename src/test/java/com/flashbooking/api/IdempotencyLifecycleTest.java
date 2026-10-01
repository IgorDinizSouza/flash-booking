package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.service.IReservationExpirationService;
import com.flashbooking.support.AbstractApiTest;
import com.flashbooking.support.StockInvariant;

import org.springframework.beans.factory.annotation.Autowired;

/** Ciclo de vida da idempotencia: replay congelado (D3), canonicalizacao, caixa da chave e concorrencia com falhas. */
class IdempotencyLifecycleTest extends AbstractApiTest {

    @Autowired
    IReservationExpirationService expiration;

    @Test
    @DisplayName("IDP-09 replay apos cancelar: resposta original congelada, sem novas linhas de historico nem estoque")
    void replayAfterCancelDoesNotTouchHistoryOrStock() {
        UUID eventId = newEvent(5);
        String key = newKey();
        var first = postReservation(eventId, key, 2);
        UUID id = UUID.fromString(first.getBody().get("id").asText());
        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        int historyBefore = count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", eventId);

        for (int i = 0; i < 2; i++) {
            var replay = postReservation(eventId, key, 2);

            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(replay.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
            assertThat(replay.getBody()).isEqualTo(first.getBody());
        }

        assertThat(get(id).getBody().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", eventId))
                .isEqualTo(historyBefore).isEqualTo(2);
        assertThat(historyCount(id, "CREATED")).isEqualTo(1);
        assertThat(historyCount(id, "CANCELLED")).isEqualTo(1);
        assertThat(reservationRows(eventId)).isEqualTo(1);
        assertThat(available(eventId)).isEqualTo(5);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("IDP-10 replay apos expirar: resposta original congelada, sem novas linhas de historico nem estoque")
    void replayAfterExpirationDoesNotTouchHistoryOrStock() {
        expiration.expireAll(1000);
        UUID eventId = newEvent(5);
        String key = newKey();
        var first = postReservation(eventId, key, 2);
        UUID id = UUID.fromString(first.getBody().get("id").asText());
        expireInPast(id);
        assertThat(expiration.expireAll(1000)).isGreaterThanOrEqualTo(1);
        assertThat(available(eventId)).isEqualTo(5);
        int historyBefore = count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", eventId);

        var replay = postReservation(eventId, key, 2);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replay.getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(get(id).getBody().get("status").asText()).isEqualTo("EXPIRED");
        assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", eventId))
                .isEqualTo(historyBefore).isEqualTo(2);
        assertThat(reservationRows(eventId)).isEqualTo(1);
        assertThat(available(eventId)).isEqualTo(5);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("IDP-12 UUID do evento em maiusculas gera o mesmo hash: replay, nao conflito")
    void upperCaseEventIdIsTheSameRequest() {
        UUID eventId = newEvent(10);
        String key = newKey();
        var first = postReservation(eventId, key, 2);

        var replay = postReservationTo("/events/" + eventId.toString().toUpperCase() + "/reservations", key,
                "{\"quantity\":2}");

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(reservationRows(eventId)).isEqualTo(1);
        assertThat(available(eventId)).isEqualTo(8);
    }

    @Test
    @DisplayName("IDP-11 canonicalizacao: espacos, ordem de campos e campos extras geram o mesmo hash")
    void equivalentBodiesAreTheSameRequest() {
        UUID eventId = newEvent(20);
        String key = newKey();
        var first = postReservation(eventId, key, "{\"quantity\":3}");

        for (String variant : List.of("{ \"quantity\" : 3 }", "{\"x\":true,\"quantity\":3,\"y\":[1,2]}",
                "{\n  \"quantity\": 3\n}")) {
            var replay = postReservation(eventId, key, variant);
            assertThat(replay.getStatusCode()).as(variant).isEqualTo(HttpStatus.CREATED);
            assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).as(variant).isEqualTo("true");
            assertThat(replay.getBody()).as(variant).isEqualTo(first.getBody());
        }
        assertThat(reservationRows(eventId)).isEqualTo(1);
        assertThat(available(eventId)).isEqualTo(17);
    }

    @Test
    @DisplayName("IDP-13 a chave diferencia maiusculas de minusculas")
    void keyIsCaseSensitive() {
        UUID eventId = newEvent(10);
        String base = UUID.randomUUID().toString();

        var upper = postReservation(eventId, "Abc-" + base, 1);
        var lower = postReservation(eventId, "abc-" + base, 1);

        assertThat(upper.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lower.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lower.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(lower.getBody().get("id").asText()).isNotEqualTo(upper.getBody().get("id").asText());
        assertThat(reservationRows(eventId)).isEqualTo(2);
        assertThat(available(eventId)).isEqualTo(8);
    }

    @Test
    @DisplayName("IDP-18 conflito nao altera a chave original: a chave continua fazendo replay da reserva original")
    void conflictDoesNotChangeTheOriginalKey() {
        UUID eventId = newEvent(10);
        String key = newKey();
        var first = postReservation(eventId, key, 2);

        assertProblem(postReservation(eventId, key, 3), HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT");
        var again = postReservation(eventId, key, 2);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.getBody()).isEqualTo(first.getBody());
        assertThat(reservationRows(eventId)).isEqualTo(1);
        assertThat(available(eventId)).isEqualTo(8);
    }

    @Test
    @DisplayName("IDP-20 varias chaves no mesmo evento criam reservas independentes (cada uma com seu replay)")
    void severalKeysOnSameEventAreIndependent() {
        UUID eventId = newEvent(10);
        List<String> keys = List.of(newKey(), newKey(), newKey());
        List<String> ids = new ArrayList<>();
        for (String k : keys) {
            var res = postReservation(eventId, k, 2);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            ids.add(res.getBody().get("id").asText());
        }
        assertThat(ids.stream().distinct()).hasSize(3);
        assertThat(available(eventId)).isEqualTo(4);

        for (int i = 0; i < keys.size(); i++) {
            var replay = postReservation(eventId, keys.get(i), 2);
            assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(replay.getBody().get("id").asText()).isEqualTo(ids.get(i));
        }
        assertThat(reservationRows(eventId)).isEqualTo(3);
        assertThat(available(eventId)).isEqualTo(4);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("IDP-14 replay concorrente com estoque esgotado: todos 409, nenhuma chave retida, nenhum 5xx")
    void concurrentSameKeyOnSoldOutEventAllGet409() throws Exception {
        UUID eventId = newEvent(1);
        assertThat(postReservation(eventId, newKey(), 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String key = newKey();
        List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> postReservation(eventId, key, 1));
        }

        var results = runConcurrently(tasks);

        assertThat(results).allSatisfy(r -> {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody().get("code").asText()).isEqualTo("INSUFFICIENT_CAPACITY");
            assertThat(r.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        });
        assertThat(keyRows(key)).isZero();
        assertThat(reservationRows(eventId)).isEqualTo(1);
        assertThat(available(eventId)).isZero();
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    @DisplayName("IDP-15 replay concorrente com liberacao de estoque no meio: no maximo uma reserva pela chave")
    void concurrentSameKeyWhileStockIsReleasedCreatesAtMostOneReservation() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID eventId = newEvent(1);
            UUID holder = UUID.fromString(postReservation(eventId, newKey(), 1).getBody().get("id").asText());
            String key = newKey();
            List<Callable<ResponseEntity<JsonNode>>> tasks = new ArrayList<>();
            tasks.add(() -> delete(holder));
            for (int i = 0; i < 12; i++) {
                tasks.add(() -> postReservation(eventId, key, 1));
            }

            var results = runConcurrently(tasks);

            assertThat(results.get(0).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            var posts = results.subList(1, results.size());
            assertThat(posts).allSatisfy(r -> assertThat(r.getStatusCode()).isIn(HttpStatus.CREATED,
                    HttpStatus.CONFLICT));
            var created = posts.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).toList();
            assertThat(created.stream().map(r -> r.getBody().get("id").asText()).distinct().count())
                    .isLessThanOrEqualTo(1);
            assertThat(created.stream().filter(r -> r.getHeaders().getFirst("Idempotent-Replayed") == null).count())
                    .isLessThanOrEqualTo(1);
            assertThat(keyRows(key)).isEqualTo(created.isEmpty() ? 0 : 1);
            assertThat(reservationRows(eventId)).isEqualTo(1 + (created.isEmpty() ? 0 : 1));
            assertThat(available(eventId)).isEqualTo(created.isEmpty() ? 1 : 0);
            StockInvariant.assertHolds(jdbc, eventId);
        }
    }
}
