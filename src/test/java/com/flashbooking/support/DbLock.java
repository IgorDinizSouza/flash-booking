package com.flashbooking.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Conexao separada que mantem uma transacao aberta (sem commit) segurando um lock, para forcar
 * timeouts nas requisicoes sob teste. {@link #close()} faz rollback e devolve a conexao.
 */
public final class DbLock implements AutoCloseable {

    private final Connection connection;

    private DbLock(Connection connection) {
        this.connection = connection;
    }

    private static DbLock open(DataSource ds, String sql) {
        try {
            Connection c = ds.getConnection();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(sql);
            }
            return new DbLock(c);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static DbLock lockEvent(DataSource ds, UUID eventId) {
        return open(ds, "SELECT id FROM events WHERE id = '" + eventId + "' FOR UPDATE");
    }

    public static DbLock lockReservation(DataSource ds, UUID reservationId) {
        return open(ds, "SELECT id FROM reservations WHERE id = '" + reservationId + "' FOR UPDATE");
    }

    public static DbLock insertIdempotencyKey(DataSource ds, String key) {
        return open(ds, "INSERT INTO idempotency_keys (idempotency_key, request_hash) VALUES ('" + key + "', 'x')");
    }

    @Override
    public void close() {
        try {
            connection.rollback();
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
