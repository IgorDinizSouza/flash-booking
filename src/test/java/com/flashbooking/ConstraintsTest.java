package com.flashbooking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.flashbooking.support.AbstractIntegrationTest;

/** I15: Flyway aplica V1..V4 e as constraints impedem estado invalido. */
class ConstraintsTest extends AbstractIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    private UUID newEvent(int capacity, int available) {
        return jdbc.sql("INSERT INTO events (name, total_capacity, available) VALUES ('e', :c, :a) RETURNING id")
                .param("c", capacity).param("a", available)
                .query(UUID.class).single();
    }

    private void insertReservation(UUID eventId, int quantity, String status) {
        jdbc.sql("""
                INSERT INTO reservations (id, event_id, quantity, status, expires_at)
                VALUES (:id, :e, :q, :s, NOW() + INTERVAL '10 minutes')""")
                .param("id", UUID.randomUUID()).param("e", eventId)
                .param("q", quantity).param("s", status)
                .update();
    }

    @Test
    void flywayAppliedAllMigrations() {
        var versions = jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list();
        assertThat(versions).containsExactly("1", "2", "3", "4");
    }

    @Test
    void validEventIsAccepted() {
        assertThat(newEvent(10, 10)).isNotNull();
    }

    @Test
    void eventCapacityMustBePositive() {
        assertThatThrownBy(() -> newEvent(0, 0)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> newEvent(-5, 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void availableCannotBeNegative() {
        assertThatThrownBy(() -> newEvent(10, -1)).isInstanceOf(DataIntegrityViolationException.class);
        UUID id = newEvent(10, 0);
        assertThatThrownBy(() -> jdbc.sql("UPDATE events SET available = available - 1 WHERE id = :id")
                .param("id", id).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void availableCannotExceedTotalCapacity() {
        assertThatThrownBy(() -> newEvent(10, 11)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reservationQuantityMustBePositive() {
        UUID event = newEvent(10, 10);
        assertThatThrownBy(() -> insertReservation(event, 0, "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReservation(event, -1, "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reservationStatusMustBeKnown() {
        UUID event = newEvent(10, 10);
        assertThatThrownBy(() -> insertReservation(event, 1, "CONFIRMED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertReservation(event, 1, "PENDING");
    }

    @Test
    void reservationRequiresExistingEvent() {
        assertThatThrownBy(() -> insertReservation(UUID.randomUUID(), 1, "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyKeyIsUnique() {
        jdbc.sql("INSERT INTO idempotency_keys (idempotency_key, request_hash) VALUES ('k', 'h')").update();
        int inserted = jdbc.sql("""
                INSERT INTO idempotency_keys (idempotency_key, request_hash) VALUES ('k', 'h2')
                ON CONFLICT DO NOTHING""").update();
        assertThat(inserted).isZero();
        assertThatThrownBy(() -> jdbc.sql(
                "INSERT INTO idempotency_keys (idempotency_key, request_hash) VALUES ('k', 'h3')").update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void historyConstraints() {
        UUID event = newEvent(10, 10);
        UUID reservation = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO reservations (id, event_id, quantity, status, expires_at)
                VALUES (:id, :e, 1, 'PENDING', NOW() + INTERVAL '10 minutes')""")
                .param("id", reservation).param("e", event).update();

        String insert = """
                INSERT INTO reservation_history (reservation_id, event_id, action, new_status, quantity, reason)
                VALUES (:r, :e, :a, :s, 1, 'CLIENT_REQUEST')""";
        assertThatThrownBy(() -> jdbc.sql(insert).param("r", reservation).param("e", event)
                .param("a", "BOGUS").param("s", "PENDING").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(insert).param("r", reservation).param("e", event)
                .param("a", "CREATED").param("s", "BOGUS").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(insert).param("r", UUID.randomUUID()).param("e", event)
                .param("a", "CREATED").param("s", "PENDING").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql(insert).param("r", reservation).param("e", event)
                .param("a", "CREATED").param("s", "PENDING").update()).isEqualTo(1);
    }
}
