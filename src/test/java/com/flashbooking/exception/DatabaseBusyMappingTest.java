package com.flashbooking.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.TransactionSystemException;

/** Mapeamento isolado (sem banco) das falhas de infraestrutura que viram 503. */
class DatabaseBusyMappingTest {

    @Test
    void deadlockSqlStateChainedUnderWrappersIsBusy() {
        var deadlock = new SQLException("deadlock detected", "40P01");
        var wrapped = new TransactionSystemException("commit failed", new RuntimeException(
                new DeadlockLoserDataAccessException("x", deadlock)));
        assertThat(GlobalExceptionHandler.isDatabaseBusy(wrapped)).isTrue();
    }

    @Test
    void lockAndStatementTimeoutSqlStatesAreBusy() {
        assertThat(GlobalExceptionHandler.isDatabaseBusy(new RuntimeException(new SQLException("l", "55P03"))))
                .isTrue();
        assertThat(GlobalExceptionHandler.isDatabaseBusy(new RuntimeException(new SQLException("s", "57014"))))
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "57P01", "57P02", "57P03", "08000", "08001", "08003", "08004", "08006", "08007" })
    void shutdownAndConnectionFailureSqlStatesAreBusy(String sqlState) {
        var wrapped = new TransactionSystemException("x", new RuntimeException(new SQLException("down", sqlState)));
        assertThat(GlobalExceptionHandler.isDatabaseBusy(wrapped)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "23505", "22001", "42P01", "57000", "0A000", "40001" })
    void unrelatedSqlStatesAreStillNotBusy(String sqlState) {
        assertThat(GlobalExceptionHandler.isDatabaseBusy(new RuntimeException(new SQLException("u", sqlState))))
                .isFalse();
    }

    @Test
    void otherSqlStatesAndPlainErrorsAreNotBusy() {
        assertThat(GlobalExceptionHandler.isDatabaseBusy(new RuntimeException(new SQLException("u", "23505"))))
                .isFalse();
        assertThat(GlobalExceptionHandler.isDatabaseBusy(new IllegalStateException("boom"))).isFalse();
    }
}
