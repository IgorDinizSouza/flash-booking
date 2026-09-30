package com.flashbooking.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flashbooking.exception.BusinessException;
import com.flashbooking.model.enums.ErrorCode;
import com.flashbooking.model.enums.ReservationStatus;
import com.flashbooking.support.AbstractReservationTest;

/** Camada de persistencia MyBatis: ReservationRepository (RETURNING, FK, status efetivo, SKIP LOCKED, ANY). */
class ReservationMapperTest extends AbstractReservationTest {

    @Autowired
    ReservationRepository reservations;

    @Autowired
    PlatformTransactionManager txManager;

    private final List<UUID> createdEvents = new java.util.ArrayList<>();

    @Override
    protected UUID newEvent(int capacity) {
        UUID id = super.newEvent(capacity);
        createdEvents.add(id);
        return id;
    }

    /** Reservas vencidas deixadas no banco compartilhado seriam expiradas (e restituiriam estoque) por outros testes. */
    @AfterEach
    void neutralizeDueReservations() {
        jdbc.sql("UPDATE reservations SET status = 'CANCELLED' WHERE status = 'PENDING' AND expires_at <= NOW() "
                + "AND event_id = ANY(:ids)").param("ids", createdEvents.toArray(new UUID[0])).update();
        createdEvents.clear();
    }

    private UUID insertReservation(UUID eventId, int quantity) {
        UUID id = UUID.randomUUID();
        reservations.insert(id, eventId, quantity, 600.0);
        return id;
    }

    /** Reservas PENDING vencidas ha 30 dias: as mais antigas do banco, logo as primeiras do ORDER BY expires_at. */
    private List<UUID> seedVeryOldExpired(UUID eventId, int count) {
        List<UUID> ids = seedExpired(eventId, count);
        jdbc.sql("UPDATE reservations SET created_at = NOW() - interval '31 days', "
                + "expires_at = NOW() - interval '30 days' + (quantity * interval '1 second') WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(new UUID[0])).update();
        return ids;
    }

    @Test
    void insertReturnsPendingReservationWithDatabaseTimestamps() {
        UUID eventId = newEvent(10);
        UUID id = UUID.randomUUID();

        var r = reservations.insert(id, eventId, 3, 600.0);

        assertThat(r.id()).isEqualTo(id);
        assertThat(r.eventId()).isEqualTo(eventId);
        assertThat(r.quantity()).isEqualTo(3);
        assertThat(r.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(r.effectiveStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(Duration.between(r.createdAt(), r.expiresAt()).toSeconds()).isBetween(599L, 601L);
        assertThat(r.updatedAt()).isEqualTo(r.createdAt());
        assertThat(physicalStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void insertAcceptsFractionalTtl() {
        UUID eventId = newEvent(10);

        var r = reservations.insert(UUID.randomUUID(), eventId, 1, 600.5);

        assertThat(Duration.between(r.createdAt(), r.expiresAt()).toMillis()).isBetween(600_400L, 600_600L);
    }

    @Test
    void insertForUnknownEventIsTranslatedFromForeignKeyViolation() {
        assertThatThrownBy(() -> reservations.insert(UUID.randomUUID(), UUID.randomUUID(), 1, 600.0))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.EVENT_NOT_FOUND));
    }

    @Test
    void findByIdMapsEveryColumn() {
        UUID eventId = newEvent(10);
        UUID id = insertReservation(eventId, 2);

        var r = reservations.findById(id).orElseThrow();

        assertThat(r.id()).isEqualTo(id);
        assertThat(r.eventId()).isEqualTo(eventId);
        assertThat(r.quantity()).isEqualTo(2);
        assertThat(r.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(r.expiresAt()).isAfter(r.createdAt());
        assertThat(reservations.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findByIdAppliesEffectiveStatusWithoutChangingThePhysicalOne() {
        UUID eventId = newEvent(10);
        UUID id = insertReservation(eventId, 1);
        expireInPast(id);

        var r = reservations.findById(id).orElseThrow();

        assertThat(r.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(r.effectiveStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(physicalStatus(id)).isEqualTo("PENDING");
    }

    @Test
    void cancelOfValidPendingReturnsEventAndQuantity() {
        UUID eventId = newEvent(10);
        UUID id = insertReservation(eventId, 4);

        var cancelled = reservations.cancelIfPendingAndNotExpired(id);

        assertThat(cancelled).contains(new ReservationRepository.Cancelled(eventId, 4));
        assertThat(physicalStatus(id)).isEqualTo("CANCELLED");
        var after = reservations.findById(id).orElseThrow();
        assertThat(after.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(after.effectiveStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void cancelIsEmptyForExpiredAlreadyCancelledAndUnknownReservations() {
        UUID eventId = newEvent(10);

        UUID due = insertReservation(eventId, 1);
        expireInPast(due);
        assertThat(reservations.cancelIfPendingAndNotExpired(due)).isEmpty();
        assertThat(physicalStatus(due)).isEqualTo("PENDING");

        UUID twice = insertReservation(eventId, 1);
        assertThat(reservations.cancelIfPendingAndNotExpired(twice)).isPresent();
        assertThat(reservations.cancelIfPendingAndNotExpired(twice)).isEmpty();

        assertThat(reservations.cancelIfPendingAndNotExpired(UUID.randomUUID())).isEmpty();
    }

    @Test
    void lockExpiredBatchReturnsOnlyDueReservationsOldestFirstUpToTheLimit() {
        UUID eventId = newEvent(20);
        List<UUID> old = seedVeryOldExpired(eventId, 4);
        UUID notDue = insertReservation(eventId, 1);

        var batch = new TransactionTemplate(txManager).execute(s -> reservations.lockExpiredBatch(2));

        assertThat(batch).hasSize(2);
        assertThat(batch).extracting(ReservationRepository.ExpiredRow::id).isSubsetOf(old).doesNotContain(notDue);
        assertThat(batch).allSatisfy(row -> {
            assertThat(row.eventId()).isEqualTo(eventId);
            assertThat(row.quantity()).isEqualTo(1);
        });
        // so trava: o estado fisico nao muda
        assertThat(countByStatus(eventId, "PENDING")).isEqualTo(5);
    }

    @Test
    void lockExpiredBatchSkipsRowsLockedByAnotherTransaction() throws Exception {
        UUID eventId = newEvent(20);
        Set<UUID> mine = new HashSet<>(seedVeryOldExpired(eventId, 6));

        var template = new TransactionTemplate(txManager);
        CountDownLatch firstHoldsLocks = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<ReservationRepository.ExpiredRow>> first = pool.submit(() -> template.execute(s -> {
                var rows = reservations.lockExpiredBatch(3);
                firstHoldsLocks.countDown();
                await(secondDone);
                return rows;
            }));
            assertThat(firstHoldsLocks.await(10, TimeUnit.SECONDS)).isTrue();

            Future<List<ReservationRepository.ExpiredRow>> second = pool.submit(() -> {
                try {
                    // nao pode bloquear nas linhas do primeiro: SKIP LOCKED pula direto para as seguintes
                    return template.execute(s -> reservations.lockExpiredBatch(3));
                } finally {
                    secondDone.countDown();
                }
            });

            Set<UUID> secondIds = idsOf(second.get(10, TimeUnit.SECONDS));
            Set<UUID> firstIds = idsOf(first.get(10, TimeUnit.SECONDS));

            assertThat(firstIds).hasSize(3);
            assertThat(secondIds).hasSize(3);
            assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
            Set<UUID> union = new HashSet<>(firstIds);
            union.addAll(secondIds);
            assertThat(union).isEqualTo(mine);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void markExpiredUpdatesOnlyTheGivenIds() {
        UUID eventId = newEvent(20);
        List<UUID> due = seedExpired(eventId, 3);
        UUID untouched = insertReservation(eventId, 1);

        int updated = reservations.markExpired(due.subList(0, 2));

        assertThat(updated).isEqualTo(2);
        assertThat(physicalStatus(due.get(0))).isEqualTo("EXPIRED");
        assertThat(physicalStatus(due.get(1))).isEqualTo("EXPIRED");
        assertThat(physicalStatus(due.get(2))).isEqualTo("PENDING");
        assertThat(physicalStatus(untouched)).isEqualTo("PENDING");
        assertThat(reservations.markExpired(List.of())).isZero();
    }

    private static Set<UUID> idsOf(List<ReservationRepository.ExpiredRow> rows) {
        Set<UUID> ids = new HashSet<>();
        rows.forEach(r -> ids.add(r.id()));
        return ids;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timeout waiting for the second transaction");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
