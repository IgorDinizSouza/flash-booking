package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import com.flashbooking.service.IReservationExpirationService;
import com.flashbooking.support.AbstractApiTest;

/**
 * HST-09/HST-04: o historico e append-only. Um trigger temporario proibe UPDATE/DELETE em reservation_history; todos os
 * fluxos da API (criar, replay, cancelar, repetir, expirar, erros) precisam funcionar mesmo assim, e as contagens de
 * linhas ficam estaveis depois de replays/erros.
 */
class AppendOnlyHistoryTest extends AbstractApiTest {

    @Autowired
    IReservationExpirationService expiration;

    private void installGuard() {
        jdbc.sql("CREATE OR REPLACE FUNCTION reservation_history_guard() RETURNS trigger AS $$ BEGIN "
                + "RAISE EXCEPTION 'reservation_history is append-only'; END; $$ LANGUAGE plpgsql").update();
        jdbc.sql("CREATE TRIGGER reservation_history_append_only BEFORE UPDATE OR DELETE ON reservation_history "
                + "FOR EACH STATEMENT EXECUTE FUNCTION reservation_history_guard()").update();
    }

    private void removeGuard() {
        jdbc.sql("DROP TRIGGER IF EXISTS reservation_history_append_only ON reservation_history").update();
        jdbc.sql("DROP FUNCTION IF EXISTS reservation_history_guard()").update();
    }

    @Test
    @DisplayName("HST-09 fluxos completos funcionam com UPDATE/DELETE proibidos no historico; linhas so crescem por transicao")
    void everyFlowWorksWhenHistoryCannotBeUpdatedOrDeleted() {
        expiration.expireAll(1000);
        installGuard();
        try {
            UUID eventId = newEvent(10);
            String key = newKey();
            var created = postReservation(eventId, key, 2);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID a = UUID.fromString(created.getBody().get("id").asText());
            assertThat(historyRows(eventId)).isEqualTo(1);

            // replay, conflito, 409 de estoque, 404, 400: nenhuma linha nova
            assertThat(postReservation(eventId, key, 2).getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(postReservation(eventId, key, 3).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(postReservation(eventId, newKey(), 10).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(postReservation(eventId, newKey(), 0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(postReservation(UUID.randomUUID(), newKey(), 1).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(historyRows(eventId)).as("apos replay/erros").isEqualTo(1);

            // cancelamento efetivo (+1), repetido e 404 (+0)
            assertThat(delete(a).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(historyRows(eventId)).isEqualTo(2);
            assertThat(delete(a).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(delete(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(historyRows(eventId)).isEqualTo(2);

            // expiracao (+1 por reserva); a segunda rodada nao escreve nada
            UUID b = reserveOk(eventId, 3);
            expireInPast(b);
            assertThat(delete(b).getStatusCode()).as("vencida: 409").isEqualTo(HttpStatus.CONFLICT);
            assertThat(expiration.expireAll(1000)).isEqualTo(1);
            assertThat(historyRows(eventId)).isEqualTo(4);
            assertThat(expiration.expireAll(1000)).isZero();
            assertThat(historyRows(eventId)).isEqualTo(4);

            // cada reserva tem no maximo CREATED + 1 transicao final, e as linhas nunca mudaram de conteudo
            List<String> actions = jdbc.sql("SELECT reservation_id || ':' || action FROM reservation_history "
                    + "WHERE event_id = :e ORDER BY id").param("e", eventId).query(String.class).list();
            assertThat(actions).containsExactly(a + ":CREATED", a + ":CANCELLED", b + ":CREATED", b + ":EXPIRED");
        } finally {
            removeGuard();
        }
    }

    @Test
    @DisplayName("HST-09 o trigger de guarda realmente bloqueia UPDATE/DELETE (prova do proprio teste)")
    void theGuardItselfBlocksUpdatesAndDeletes() {
        installGuard();
        try {
            UUID eventId = newEvent(3);
            reserveOk(eventId, 1);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> jdbc.sql("UPDATE reservation_history SET quantity = 9 WHERE event_id = :e")
                            .param("e", eventId).update()).hasMessageContaining("append-only");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> jdbc.sql("DELETE FROM reservation_history WHERE event_id = :e").param("e", eventId).update())
                    .hasMessageContaining("append-only");
            assertThat(historyRows(eventId)).isEqualTo(1);
        } finally {
            removeGuard();
        }
    }

    private int historyRows(UUID eventId) {
        return count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", eventId);
    }
}
