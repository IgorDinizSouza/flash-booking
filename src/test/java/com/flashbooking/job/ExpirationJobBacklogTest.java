package com.flashbooking.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import com.flashbooking.service.IReservationExpirationService;
import com.flashbooking.support.AbstractApiTest;
import com.flashbooking.support.StockInvariant;

/**
 * EXP-19: o ciclo do job drena no maximo 20 lotes (2.000 reservas com batch 100); o restante fica para os ciclos seguintes.
 * Bean do job LIGADO mas com intervalo de 1h: o teste dispara {@code run()} manualmente, ciclo a ciclo (deterministico).
 */
@TestPropertySource(properties = {
        "booking.reservation.expiration-job-enabled=true",
        "booking.reservation.expiration-job-delay=1h"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExpirationJobBacklogTest extends AbstractApiTest {

    @Autowired
    ReservationExpirationJob job;

    @Autowired
    IReservationExpirationService expiration;

    @Test
    @DisplayName("EXP-19 backlog de 2.500 vencidas: o 1o ciclo expira no maximo 2.000 (20 lotes de 100); o 2o drena o resto")
    void oneCycleIsCappedAtTwentyBatches() throws Exception {
        Thread.sleep(1_500); // deixa passar a execucao inicial do @Scheduled, antes de semear
        expiration.expireAll(1_000);
        UUID eventId = newEvent(3_000);
        seedExpired(eventId, 2_500);
        assertThat(available(eventId)).isEqualTo(500);

        job.run();

        assertThat(countByStatus(eventId, "EXPIRED")).as("1o ciclo").isEqualTo(2_000);
        assertThat(countByStatus(eventId, "PENDING")).isEqualTo(500);
        assertThat(available(eventId)).isEqualTo(2_500);

        job.run();

        assertThat(countByStatus(eventId, "EXPIRED")).as("2o ciclo").isEqualTo(2_500);
        assertThat(countByStatus(eventId, "PENDING")).isZero();
        assertThat(available(eventId)).isEqualTo(3_000);
        assertThat(historyCountForEvent(eventId, "EXPIRED")).isEqualTo(2_500);
        StockInvariant.assertHolds(jdbc, eventId);
    }
}
