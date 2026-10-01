package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.flashbooking.service.IReservationExpirationService;
import com.flashbooking.support.AbstractApiTest;
import com.flashbooking.support.DbLock;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Contadores reservations.* (OBS-01..07, EXP-18): +1 exatamente uma vez no sucesso efetivo e +0 em rollback, replay e
 * DELETE repetido. Sempre por delta (o MeterRegistry e compartilhado com as outras classes do mesmo contexto).
 */
@TestPropertySource(properties = "booking.db.lock-timeout=300ms")
class MetricsTest extends AbstractApiTest {

    @Autowired
    MeterRegistry meters;

    @Autowired
    IReservationExpirationService expiration;

    @Autowired
    DataSource dataSource;

    private double created() {
        return meters.counter("reservations.created").count();
    }

    private double cancelled() {
        return meters.counter("reservations.cancelled").count();
    }

    private double expired() {
        return meters.counter("reservations.expired").count();
    }

    private double rejected(String reason) {
        return meters.counter("reservations.rejected", "reason", reason).count();
    }

    @Test
    @DisplayName("OBS-01 reservations.created sobe +1 por reserva criada e +0 no replay")
    void createdCountsOnlyTheEffectiveCreation() {
        UUID eventId = newEvent(10);
        String key = newKey();
        double before = created();

        assertThat(postReservation(eventId, key, 2).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created() - before).isEqualTo(1.0);

        var replay = postReservation(eventId, key, 2);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(created() - before).as("replay nao incrementa").isEqualTo(1.0);

        assertThat(postReservation(eventId, newKey(), 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created() - before).isEqualTo(2.0);
    }

    @Test
    @DisplayName("OBS-02 reservations.created nao sobe em rollback (409 estoque, 404, 400, 503)")
    void createdDoesNotCountRollbacks() {
        UUID soldOut = newEvent(1);
        assertThat(postReservation(soldOut, newKey(), 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID locked = newEvent(5);
        double createdBefore = created();
        double busyBefore = rejected("db_busy");

        assertThat(postReservation(soldOut, newKey(), 1).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(postReservation(UUID.randomUUID(), newKey(), 1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(postReservation(soldOut, newKey(), 0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        try (DbLock ignored = DbLock.lockEvent(dataSource, locked)) {
            assertThat(postReservation(locked, newKey(), 1).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        assertThat(created() - createdBefore).isZero();
        assertThat(rejected("db_busy") - busyBefore).as("503 conta como db_busy").isEqualTo(1.0);
        assertThat(available(locked)).isEqualTo(5);
    }

    @Test
    @DisplayName("OBS-03 reservations.cancelled: DELETE efetivo +1, repetido +0, 409 +0, 404 +0")
    void cancelledCountsOnlyTheEffectiveCancellation() {
        UUID eventId = newEvent(5);
        UUID id = reserveOk(eventId, 1);
        UUID dueAndPending = reserveOk(eventId, 1);
        expireInPast(dueAndPending);
        double before = cancelled();

        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(cancelled() - before).isEqualTo(1.0);

        assertThat(delete(id).getStatusCode()).as("DELETE repetido").isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(cancelled() - before).isEqualTo(1.0);

        assertThat(delete(dueAndPending).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(delete(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(cancelled() - before).isEqualTo(1.0);
        expiration.expireAll(1000); // nao deixa a vencida pendurada no banco compartilhado
    }

    @Test
    @DisplayName("OBS-04/EXP-18 reservations.expired sobe pelo tamanho do lote; lote vazio +0")
    void expiredCountsTheBatchSize() {
        expiration.expireAll(1000); // drena sobras de outros testes para o delta ser exato
        double before = expired();
        assertThat(expiration.expireBatch()).isZero();
        assertThat(expired() - before).as("lote vazio").isZero();

        UUID eventId = newEvent(50);
        seedExpired(eventId, 7);
        UUID live = reserveOk(eventId, 1);

        assertThat(expiration.expireAll(1000)).isEqualTo(7);

        assertThat(expired() - before).isEqualTo(7.0);
        assertThat(physicalStatus(live)).isEqualTo("PENDING");
        assertThat(expiration.expireAll(1000)).isZero();
        assertThat(expired() - before).as("segunda rodada nao conta de novo").isEqualTo(7.0);
    }

    @Test
    @DisplayName("OBS-05 rejected{insufficient_capacity}: +1 por resposta 409 de estoque")
    void rejectedInsufficientCapacity() {
        UUID eventId = newEvent(1);
        assertThat(postReservation(eventId, newKey(), 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        double before = rejected("insufficient_capacity");

        assertThat(postReservation(eventId, newKey(), 1).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected("insufficient_capacity") - before).isEqualTo(1.0);
        assertThat(postReservation(eventId, newKey(), 5).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected("insufficient_capacity") - before).isEqualTo(2.0);
    }

    @Test
    @DisplayName("OBS-06 rejected{idempotency_conflict}: +1 por conflito; replay valido nao conta")
    void rejectedIdempotencyConflict() {
        UUID eventId = newEvent(10);
        String key = newKey();
        assertThat(postReservation(eventId, key, 2).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        double before = rejected("idempotency_conflict");
        double capBefore = rejected("insufficient_capacity");

        assertThat(postReservation(eventId, key, 3).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected("idempotency_conflict") - before).isEqualTo(1.0);
        assertThat(postReservation(eventId, key, 2).getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");

        assertThat(rejected("idempotency_conflict") - before).isEqualTo(1.0);
        assertThat(rejected("insufficient_capacity") - capBefore).isZero();
    }

    @Test
    @DisplayName("OBS-07 rejected{invalid_state}: +1 no DELETE de reserva vencida/EXPIRED; 404 e 204 nao contam")
    void rejectedInvalidState() {
        expiration.expireAll(1000);
        UUID eventId = newEvent(5);
        UUID due = reserveOk(eventId, 1);
        UUID done = reserveOk(eventId, 1);
        expireInPast(due);
        expireInPast(done);
        expiration.expireBatch(); // done e due viram EXPIRED fisico
        UUID pendingDue = reserveOk(eventId, 1);
        expireInPast(pendingDue);
        double before = rejected("invalid_state");

        assertThat(delete(done).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected("invalid_state") - before).isEqualTo(1.0);
        assertThat(delete(pendingDue).getStatusCode()).as("PENDING ja vencida").isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected("invalid_state") - before).isEqualTo(2.0);
        assertThat(delete(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rejected("invalid_state") - before).isEqualTo(2.0);
        expiration.expireAll(1000);
    }
}
