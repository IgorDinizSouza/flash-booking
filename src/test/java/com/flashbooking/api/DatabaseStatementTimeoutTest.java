package com.flashbooking.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.flashbooking.support.AbstractReservationTest;
import com.flashbooking.support.DbLock;
import com.flashbooking.support.StockInvariant;

/**
 * statement_timeout (57014) vira 503: com lock_timeout longo e statement_timeout curto, a espera pelo
 * lock e cancelada pelo statement_timeout.
 */
@TestPropertySource(properties = { "booking.db.lock-timeout=10s", "booking.db.statement-timeout=400ms" })
class DatabaseStatementTimeoutTest extends AbstractReservationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void statementTimeoutReturns503AndLeavesNothingBehind() {
        UUID eventId = newEvent(5);
        UUID id = reserveOk(eventId, 1);

        try (DbLock lock = DbLock.lockReservation(dataSource, id)) {
            DatabaseBusyTest.assertBusy(delete(id));
        }
        assertThat(physicalStatus(id)).isEqualTo("PENDING");
        assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(StockInvariant.available(jdbc, eventId)).isEqualTo(5);
        StockInvariant.assertHolds(jdbc, eventId);
    }
}
