package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.http.HttpStatus;

import com.flashbooking.repository.EventRepository;
import com.flashbooking.repository.ReservationRepository;
import com.flashbooking.support.AbstractApiTest;

/**
 * ERR-22: um erro de banco NAO mapeado (SQL invalido, por exemplo) atravessa a API real e vira 500 INTERNAL_ERROR sem
 * vazar SQL, nomes de classe, stack nem a mensagem do driver; a transacao e revertida e a chave de idempotencia nao e retida.
 */
class InternalErrorMaskingTest extends AbstractApiTest {

    @MockBean
    EventRepository events;

    @MockBean
    ReservationRepository reservations;

    private static final String LEAK = "ERROR: syntax error at or near \"SELEC\" Position: 8 org.postgresql.util.PSQLException";

    @Test
    @DisplayName("ERR-22 erro SQL inesperado em POST /events: 500 INTERNAL_ERROR generico, sem vazar detalhes")
    void unexpectedSqlErrorOnEventCreationIsMasked() {
        when(events.insert(anyString(), anyInt())).thenThrow(new BadSqlGrammarException("insert",
                "SELEC secret_column FROM events", new SQLException(LEAK, "42601")));

        var res = postEvent("{\"name\":\"x\",\"capacity\":1}");

        assertMasked(res.getStatusCode(), res.getBody(), "/events");
        assertThat(res.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(res.getBody().get("correlationId").asText()).isEqualTo(res.getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    @DisplayName("ERR-22 erro SQL inesperado em POST de reserva: 500 mascarado, rollback e chave nao retida")
    void unexpectedSqlErrorOnReservationIsMaskedAndRolledBack() {
        UUID eventId = newEvent(5);
        when(reservations.insert(any(UUID.class), any(UUID.class), anyInt(), anyDouble()))
                .thenThrow(new BadSqlGrammarException("insert", "SELEC secret FROM reservations",
                        new SQLException(LEAK, "42601")));
        String key = newKey();

        var res = postReservation(eventId, key, 2);

        assertMasked(res.getStatusCode(), res.getBody(), "/events/" + eventId + "/reservations");
        assertThat(keyRows(key)).as("chave nao retida").isZero();
        assertThat(available(eventId)).isEqualTo(5);
        assertThat(count("SELECT COUNT(*) FROM reservation_history WHERE event_id = ?", eventId)).isZero();
    }

    private void assertMasked(org.springframework.http.HttpStatusCode status, com.fasterxml.jackson.databind.JsonNode body,
            String instance) {
        assertThat(status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body.get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.get("status").asInt()).isEqualTo(500);
        assertThat(body.get("instance").asText()).isEqualTo(instance);
        String text = body.toString().toLowerCase();
        assertThat(text).doesNotContain("syntax", "selec", "secret", "postgres", "psql", "sql", "position", "exception",
                "at org.", "at com.", "stacktrace", "trace", "42601");
    }
}
