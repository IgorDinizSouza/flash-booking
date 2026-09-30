package com.flashbooking.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Invariante de estoque (PLANO.md 3.1): total_capacity = available + SUM(quantity PENDING) e
 * available >= 0. Chamar ao fim de todo teste de escrita.
 */
public final class StockInvariant {

    private StockInvariant() {
    }

    public static void assertHolds(JdbcClient jdbc, UUID eventId) {
        var row = jdbc.sql("SELECT e.total_capacity, e.available, "
                + "COALESCE((SELECT SUM(r.quantity) FROM reservations r "
                + "           WHERE r.event_id = e.id AND r.status = 'PENDING'), 0) AS pending "
                + "FROM events e WHERE e.id = :id")
                .param("id", eventId)
                .query((rs, n) -> new long[] {rs.getLong("total_capacity"), rs.getLong("available"),
                        rs.getLong("pending")})
                .single();
        assertThat(row[1]).as("available >= 0").isGreaterThanOrEqualTo(0);
        assertThat(row[0]).as("total_capacity = available + SUM(PENDING)").isEqualTo(row[1] + row[2]);
    }

    public static int available(JdbcClient jdbc, UUID eventId) {
        return jdbc.sql("SELECT available FROM events WHERE id = :id").param("id", eventId)
                .query(Integer.class).single();
    }
}
