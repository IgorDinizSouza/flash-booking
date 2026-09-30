package com.flashbooking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.flashbooking.support.AbstractReservationTest;
import com.flashbooking.support.StockInvariant;

/** I7, I8 e I9: expiracao concorrente (lotes pequenos forcam varios lotes e disputa por SKIP LOCKED). */
@TestPropertySource(properties = "booking.reservation.expiration-batch-size=7")
class ReservationExpirationConcurrencyTest extends AbstractReservationTest {

    @Autowired
    IReservationExpirationService expiration;

    private List<Callable<Integer>> expirers(int n) {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(() -> expiration.expireAll(1000));
        }
        return tasks;
    }

    @Test
    void i7_concurrentExpirationExpiresEachReservationExactlyOnce() throws Exception {
        UUID eventId = newEvent(60);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ids.add(reserveOk(eventId, 1));
        }
        jdbc.sql("UPDATE reservations SET created_at = NOW() - interval '2 hours', "
                + "expires_at = NOW() - interval '1 hour' WHERE event_id = :e").param("e", eventId).update();
        assertThat(StockInvariant.available(jdbc, eventId)).isZero();

        List<Integer> counts = runConcurrently(expirers(4));

        assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isGreaterThanOrEqualTo(60);
        assertThat(countByStatus(eventId, "EXPIRED")).isEqualTo(60);
        assertThat(countByStatus(eventId, "PENDING")).isZero();
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(60);
        assertThat(historyCountForEvent(eventId, "EXPIRED")).isEqualTo(60);
        for (UUID id : ids) {
            assertThat(historyCount(id, "EXPIRED")).isEqualTo(1);
        }
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void i7_instancesShareTheWorkViaSkipLocked() throws Exception {
        UUID eventId = newEvent(400);
        seedExpired(eventId, 400); // ~58 lotes de 7 disputados por 4 threads

        runConcurrently(expirers(4));

        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(400);
        assertThat(historyCountForEvent(eventId, "EXPIRED")).isEqualTo(400);
        var perJob = jdbc.sql("SELECT correlation_id, COUNT(*) FROM reservation_history WHERE event_id = :e "
                + "AND action = 'EXPIRED' GROUP BY correlation_id").param("e", eventId)
                .query((rs, n) -> rs.getInt(2)).list();
        assertThat(perJob).as("mais de uma execucao do job dividiu o trabalho").hasSizeGreaterThan(1);
        assertThat(perJob.stream().mapToInt(Integer::intValue).sum()).isEqualTo(400);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void i8_mixedBatchesAcrossEventsNeverDeadlock() throws Exception {
        int events = 4;
        int perEvent = 20;
        for (int round = 0; round < 20; round++) {
            List<UUID> eventIds = new ArrayList<>();
            for (int e = 0; e < events; e++) {
                UUID id = newEvent(100);
                eventIds.add(id);
                seedExpired(id, perEvent); // mesmos expires_at entre eventos: lotes de 7 misturam os eventos
            }

            runConcurrently(expirers(2)); // qualquer excecao (ex.: 40P01) propaga e falha o teste

            for (UUID id : eventIds) {
                assertThat(StockInvariant.available(jdbc, id)).as("round %d", round).isEqualTo(100);
                assertThat(countByStatus(id, "EXPIRED")).isEqualTo(perEvent);
                assertThat(historyCountForEvent(id, "EXPIRED")).isEqualTo(perEvent);
                StockInvariant.assertHolds(jdbc, id);
            }
        }
    }

    @Test
    void i9_deleteOfDueReservationRacingWithExpirationReturnsStockOnce() throws Exception {
        UUID eventId = newEvent(10);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID id = reserveOk(eventId, 1);
            expireInPast(id);
            ids.add(id);
        }

        List<Callable<Integer>> tasks = new ArrayList<>(expirers(2));
        for (UUID id : ids) {
            tasks.add(() -> delete(id).getStatusCode().value());
        }
        List<Integer> results = runConcurrently(tasks);

        // vencidas: DELETE sempre 409 (antes ou depois do job); so o job devolve estoque
        assertThat(results.subList(2, results.size())).containsOnly(HttpStatus.CONFLICT.value());
        assertThat(countByStatus(eventId, "EXPIRED")).isEqualTo(10);
        assertThat(historyCountForEvent(eventId, "CANCELLED")).isZero();
        assertThat(historyCountForEvent(eventId, "EXPIRED")).isEqualTo(10);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(10);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void i9_cancellingValidReservationsWhileJobExpiresOthersOfSameEvent() throws Exception {
        UUID eventId = newEvent(40);
        List<UUID> valid = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            valid.add(reserveOk(eventId, 1));
        }
        seedExpired(eventId, 30);

        List<Callable<Integer>> tasks = new ArrayList<>(expirers(2));
        for (UUID id : valid) {
            tasks.add(() -> delete(id).getStatusCode().value());
        }
        List<Integer> results = runConcurrently(tasks);

        assertThat(results.subList(2, results.size())).containsOnly(HttpStatus.NO_CONTENT.value());
        assertThat(countByStatus(eventId, "CANCELLED")).isEqualTo(10);
        assertThat(countByStatus(eventId, "EXPIRED")).isEqualTo(30);
        assertThat(countByStatus(eventId, "PENDING")).isZero();
        assertThat(historyCountForEvent(eventId, "CANCELLED")).isEqualTo(10);
        assertThat(historyCountForEvent(eventId, "EXPIRED")).isEqualTo(30);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(40);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void i9_cancelRacingExactlyAtTheExpiryBoundaryReturnsStockExactlyOnce() throws Exception {
        UUID eventId = newEvent(15);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            ids.add(reserveOk(eventId, 1));
        }
        jdbc.sql("UPDATE reservations SET expires_at = NOW() + interval '400 milliseconds' WHERE event_id = :e")
                .param("e", eventId).update();
        Thread.sleep(380); // largada colada na fronteira do vencimento

        List<Callable<Integer>> tasks = new ArrayList<>(expirers(2));
        for (UUID id : ids) {
            tasks.add(() -> delete(id).getStatusCode().value());
        }
        List<Integer> results = runConcurrently(tasks);
        assertThat(results.subList(2, results.size())).isSubsetOf(204, 409);

        Thread.sleep(50);
        expiration.expireAll(1000); // o que ficou PENDING vencido e expirado agora

        for (UUID id : ids) {
            int cancelled = historyCount(id, "CANCELLED");
            int expired = historyCount(id, "EXPIRED");
            assertThat(cancelled + expired).as("exatamente uma transicao final para %s", id).isEqualTo(1);
            assertThat(physicalStatus(id)).isEqualTo(cancelled == 1 ? "CANCELLED" : "EXPIRED");
        }
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(15);
        StockInvariant.assertHolds(jdbc, eventId);
    }
}
