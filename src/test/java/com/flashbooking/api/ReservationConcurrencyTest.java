package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;

import com.flashbooking.support.AbstractReservationTest;
import com.flashbooking.support.StockInvariant;

/** I3: zero oversell sob disputa (uma instancia). A prova multi-instancia esta em MultiInstanceConcurrencyTest. */
class ReservationConcurrencyTest extends AbstractReservationTest {

    private List<Callable<Integer>> attempts(UUID eventId, int count, int quantity) {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(() -> reserve(eventId, quantity).getStatusCode().value());
        }
        return tasks;
    }

    private static long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    private void assertOnlyCreatedOrConflict(List<Integer> statuses) {
        assertThat(statuses.stream().filter(s -> s != 201 && s != 409).toList())
                .as("unexpected statuses (e.g. 503)").isEmpty();
    }

    @Test
    void twoHundredParallelRequestsOnFiftyTicketsNeverOversell() throws Exception {
        UUID eventId = newEvent(50);

        List<Integer> statuses = runConcurrently(attempts(eventId, 200, 1));

        assertOnlyCreatedOrConflict(statuses);
        assertThat(count(statuses, 201)).isEqualTo(50);
        assertThat(count(statuses, 409)).isEqualTo(150);
        assertThat(StockInvariant.available(jdbc, eventId)).isZero();
        assertThat(countByStatus(eventId, "PENDING")).isEqualTo(50);
        assertThat(historyCountForEvent(eventId, "CREATED")).isEqualTo(50);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void quantityTwoOnOddCapacityLeavesOneTicketAndSellsTwentyFive() throws Exception {
        UUID eventId = newEvent(51);

        List<Integer> statuses = runConcurrently(attempts(eventId, 100, 2));

        assertOnlyCreatedOrConflict(statuses);
        assertThat(count(statuses, 201)).isEqualTo(25);
        assertThat(count(statuses, 409)).isEqualTo(75);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(1);
        assertThat(countByStatus(eventId, "PENDING")).isEqualTo(25);
        assertThat(historyCountForEvent(eventId, "CREATED")).isEqualTo(25);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void mixedQuantitiesNeverExceedCapacity() throws Exception {
        UUID eventId = newEvent(60);
        List<Callable<int[]>> tasks = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            int quantity = 1 + (i % 5); // 1..5
            tasks.add(() -> new int[] {reserve(eventId, quantity).getStatusCode().value(), quantity});
        }

        List<int[]> results = runConcurrently(tasks);

        assertThat(results.stream().filter(r -> r[0] != 201 && r[0] != 409).toList()).isEmpty();
        int sold = results.stream().filter(r -> r[0] == 201).mapToInt(r -> r[1]).sum();
        assertThat(sold).isLessThanOrEqualTo(60);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(60 - sold);
        assertThat(countByStatus(eventId, "PENDING")).isEqualTo((int) results.stream().filter(r -> r[0] == 201).count());
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void singleTicketDisputedByOneHundredSellsExactlyOne() throws Exception {
        UUID eventId = newEvent(1);

        List<Integer> statuses = runConcurrently(attempts(eventId, 100, 1));

        assertOnlyCreatedOrConflict(statuses);
        assertThat(count(statuses, 201)).isEqualTo(1);
        assertThat(count(statuses, 409)).isEqualTo(99);
        assertThat(StockInvariant.available(jdbc, eventId)).isZero();
        assertThat(countByStatus(eventId, "PENDING")).isEqualTo(1);
        assertThat(historyCountForEvent(eventId, "CREATED")).isEqualTo(1);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    @Test
    void severalEventsDisputedAtTheSameTimeKeepEachInvariant() throws Exception {
        int[] capacities = {5, 20, 33, 1, 50};
        List<UUID> events = new ArrayList<>();
        List<Callable<int[]>> tasks = new ArrayList<>();
        for (int c : capacities) {
            events.add(newEvent(c));
        }
        for (int i = 0; i < 300; i++) {
            int idx = i % events.size();
            UUID eventId = events.get(idx);
            tasks.add(() -> new int[] {idx, reserve(eventId, 1).getStatusCode().value()});
        }

        List<int[]> results = runConcurrently(tasks);

        assertThat(results.stream().filter(r -> r[1] != 201 && r[1] != 409).toList()).isEmpty();
        for (int idx = 0; idx < events.size(); idx++) {
            int i = idx;
            UUID eventId = events.get(idx);
            long created = results.stream().filter(r -> r[0] == i && r[1] == 201).count();
            assertThat(created).as("created for event " + i).isEqualTo(capacities[idx]);
            assertThat(StockInvariant.available(jdbc, eventId)).isZero();
            assertThat(countByStatus(eventId, "PENDING")).isEqualTo(capacities[idx]);
            assertThat(historyCountForEvent(eventId, "CREATED")).isEqualTo(capacities[idx]);
            StockInvariant.assertHolds(jdbc, eventId);
        }
    }
}
