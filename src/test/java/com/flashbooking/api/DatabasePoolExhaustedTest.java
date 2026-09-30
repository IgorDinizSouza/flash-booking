package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import io.micrometer.core.instrument.MeterRegistry;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.support.AbstractReservationTest;
import com.flashbooking.support.StockInvariant;

/** Pool esgotado (Hikari connection-timeout) vira 503 DATABASE_BUSY, nunca 500. */
@TestPropertySource(properties = { "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=2", "spring.datasource.hikari.connection-timeout=500" })
class DatabasePoolExhaustedTest extends AbstractReservationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    MeterRegistry meters;

    @Test
    void exhaustedPoolReturns503ThenRecovers() throws SQLException {
        UUID eventId = newEvent(5);
        UUID id = reserveOk(eventId, 1);

        List<Connection> held = new ArrayList<>();
        try {
            held.add(dataSource.getConnection());
            held.add(dataSource.getConnection());

            DatabaseBusyTest.assertBusy(reserve(eventId, 1));
            DatabaseBusyTest.assertBusy(delete(id));
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }

        assertThat(reserve(eventId, 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(4);
        StockInvariant.assertHolds(jdbc, eventId);
    }

    /** D9: o contador db_busy conta so operacoes de reserva (POST/DELETE); 503 de GET nao entra. */
    @Test
    void dbBusyCounterCountsReservationWritesButNotReads() throws SQLException {
        UUID eventId = newEvent(5);
        UUID id = reserveOk(eventId, 1);
        double before = busyCount();

        List<Connection> held = new ArrayList<>();
        try {
            held.add(dataSource.getConnection());
            held.add(dataSource.getConnection());

            DatabaseBusyTest.assertBusy(rest.getForEntity("/events/" + eventId, JsonNode.class));
            DatabaseBusyTest.assertBusy(get(id));
            assertThat(busyCount()).isEqualTo(before);

            DatabaseBusyTest.assertBusy(reserve(eventId, 1));
            assertThat(busyCount()).isEqualTo(before + 1);
            DatabaseBusyTest.assertBusy(delete(id));
            assertThat(busyCount()).isEqualTo(before + 2);
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
    }

    private double busyCount() {
        return meters.counter("reservations.rejected", "reason", "db_busy").count();
    }
}
