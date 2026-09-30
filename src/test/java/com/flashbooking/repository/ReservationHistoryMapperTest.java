package com.flashbooking.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flashbooking.model.enums.HistoryAction;
import com.flashbooking.model.enums.ReservationStatus;
import com.flashbooking.support.AbstractReservationTest;

/** Camada de persistencia MyBatis: ReservationHistoryRepository (insert simples e em lote). */
class ReservationHistoryMapperTest extends AbstractReservationTest {

    @Autowired
    ReservationHistoryRepository history;

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

    private Map<String, Object> row(UUID reservationId) {
        return jdbc.sql("SELECT action, previous_status, new_status, quantity, reason, correlation_id, instance_id "
                + "FROM reservation_history WHERE reservation_id = :id").param("id", reservationId)
                .query().singleRow();
    }

    private UUID newReservation(UUID eventId) {
        UUID id = UUID.randomUUID();
        reservations.insert(id, eventId, 1, 600.0);
        return id;
    }

    @Test
    void insertPersistsAllColumns() {
        UUID eventId = newEvent(10);
        UUID id = newReservation(eventId);

        history.insert(id, eventId, HistoryAction.CANCELLED, ReservationStatus.PENDING,
                ReservationStatus.CANCELLED, 2, "CLIENT_REQUEST", "corr-1", "inst-1");

        var r = row(id);
        assertThat(r).containsEntry("action", "CANCELLED").containsEntry("previous_status", "PENDING")
                .containsEntry("new_status", "CANCELLED").containsEntry("quantity", 2)
                .containsEntry("reason", "CLIENT_REQUEST").containsEntry("correlation_id", "corr-1")
                .containsEntry("instance_id", "inst-1");
    }

    @Test
    void insertAcceptsNullPreviousStatusAndNullAuditFields() {
        UUID eventId = newEvent(10);
        UUID id = newReservation(eventId);

        history.insert(id, eventId, HistoryAction.CREATED, null, ReservationStatus.PENDING, 1,
                "CLIENT_REQUEST", null, null);

        var r = row(id);
        assertThat(r.get("previous_status")).isNull();
        assertThat(r.get("correlation_id")).isNull();
        assertThat(r.get("instance_id")).isNull();
        assertThat(r).containsEntry("action", "CREATED").containsEntry("new_status", "PENDING");
    }

    @Test
    void insertBatchWritesOneRowPerEntryWithSharedFields() {
        UUID eventId = newEvent(10);
        UUID a = newReservation(eventId);
        UUID b = newReservation(eventId);
        UUID c = newReservation(eventId);

        new TransactionTemplate(txManager).executeWithoutResult(s -> history.insertBatch(
                List.of(new ReservationHistoryRepository.Entry(a, eventId, 1),
                        new ReservationHistoryRepository.Entry(b, eventId, 2),
                        new ReservationHistoryRepository.Entry(c, eventId, 3)),
                HistoryAction.EXPIRED, ReservationStatus.PENDING, ReservationStatus.EXPIRED, "TTL_EXPIRED",
                "job-1", "inst-1"));

        assertThat(historyCountForEvent(eventId, "EXPIRED")).isEqualTo(3);
        assertThat(row(b)).containsEntry("action", "EXPIRED").containsEntry("previous_status", "PENDING")
                .containsEntry("new_status", "EXPIRED").containsEntry("quantity", 2)
                .containsEntry("reason", "TTL_EXPIRED").containsEntry("correlation_id", "job-1")
                .containsEntry("instance_id", "inst-1");
    }

    @Test
    void insertBatchIsAtomicWithTheEnclosingTransaction() {
        UUID eventId = newEvent(10);
        UUID a = newReservation(eventId);

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            history.insertBatch(List.of(new ReservationHistoryRepository.Entry(a, eventId, 1)),
                    HistoryAction.EXPIRED, ReservationStatus.PENDING, ReservationStatus.EXPIRED, "TTL_EXPIRED",
                    null, null);
            s.setRollbackOnly();
        });

        assertThat(historyCount(a, "EXPIRED")).isZero();
    }

    @Test
    void insertBatchWithEmptyListIsANoOp() {
        history.insertBatch(List.of(), HistoryAction.EXPIRED, ReservationStatus.PENDING,
                ReservationStatus.EXPIRED, "TTL_EXPIRED", null, null);
    }

    @Test
    void insertBatchSplitsVeryLargeBatchesIntoChunks() {
        int n = ReservationHistoryRepository.BATCH_CHUNK + 1;
        UUID eventId = newEvent(n + 10);
        List<UUID> ids = seedExpired(eventId, n);
        var entries = ids.stream().map(id -> new ReservationHistoryRepository.Entry(id, eventId, 1)).toList();

        new TransactionTemplate(txManager).executeWithoutResult(s -> history.insertBatch(entries,
                HistoryAction.EXPIRED, ReservationStatus.PENDING, ReservationStatus.EXPIRED, "TTL_EXPIRED",
                null, null));

        assertThat(historyCountForEvent(eventId, "EXPIRED")).isEqualTo(n);
    }
}
