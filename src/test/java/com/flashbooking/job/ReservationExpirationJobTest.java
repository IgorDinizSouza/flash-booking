package com.flashbooking.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.flashbooking.support.AbstractReservationTest;
import com.flashbooking.support.StockInvariant;

/** Job LIGADO em contexto proprio (descartado ao fim, para nao expirar dados de outros testes). */
@TestPropertySource(properties = {
        "booking.reservation.expiration-job-enabled=true",
        "booking.reservation.expiration-job-delay=200ms"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationExpirationJobTest extends AbstractReservationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void scheduledJobExpiresDueReservationsAndReturnsStockWithoutManualCall() {
        assertThat(context.getBeansOfType(ReservationExpirationJob.class)).hasSize(1);
        UUID eventId = newEvent(10);
        UUID id = reserveOk(eventId, 4);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(6);

        expireInPast(id);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(physicalStatus(id)).isEqualTo("EXPIRED"));
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(10);
        assertThat(historyCount(id, "EXPIRED")).isEqualTo(1);
        StockInvariant.assertHolds(jdbc, eventId);
    }
}
