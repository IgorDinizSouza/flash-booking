package com.flashbooking.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.flashbooking.support.AbstractReservationTest;
import com.flashbooking.support.StockInvariant;

/** Camada de persistencia MyBatis: EventRepository (resources/mapper/EventRepository.xml). */
class EventMapperTest extends AbstractReservationTest {

    @Autowired
    EventRepository events;

    @Test
    void insertReturningMapsTheCreatedRow() {
        var event = events.insert("Show", 50);

        assertThat(event.id()).isNotNull();
        assertThat(event.name()).isEqualTo("Show");
        assertThat(event.totalCapacity()).isEqualTo(50);
        assertThat(event.available()).isEqualTo(50);
        assertThat(event.createdAt()).isNotNull();
        assertThat(StockInvariant.available(jdbc, event.id())).isEqualTo(50);
    }

    @Test
    void findByIdReturnsEventOrEmpty() {
        var created = events.insert("Find me", 7);

        assertThat(events.findById(created.id())).contains(created);
        assertThat(events.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void decrementTakesStockWhenAvailableCoversTheQuantity() {
        UUID id = newEvent(5);

        assertThat(events.decrementIfAvailable(id, 3)).isEqualTo(1);
        assertThat(StockInvariant.available(jdbc, id)).isEqualTo(2);
        // limite exato: available == quantity ainda baixa
        assertThat(events.decrementIfAvailable(id, 2)).isEqualTo(1);
        assertThat(StockInvariant.available(jdbc, id)).isZero();
    }

    @Test
    void decrementAffectsZeroRowsAndKeepsStockWhenInsufficient() {
        UUID id = newEvent(2);

        assertThat(events.decrementIfAvailable(id, 3)).isZero();
        assertThat(StockInvariant.available(jdbc, id)).isEqualTo(2);
    }

    @Test
    void incrementGivesStockBack() {
        UUID id = newEvent(5);
        events.decrementIfAvailable(id, 4);

        assertThat(events.increment(id, 4)).isEqualTo(1);
        assertThat(StockInvariant.available(jdbc, id)).isEqualTo(5);
        assertThat(events.increment(UUID.randomUUID(), 1)).isZero();
    }

    @Test
    void incrementBeyondCapacityFailsLoudlyViaCheckConstraint() {
        UUID id = newEvent(5);

        assertThatThrownBy(() -> events.increment(id, 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(StockInvariant.available(jdbc, id)).isEqualTo(5);
    }
}
