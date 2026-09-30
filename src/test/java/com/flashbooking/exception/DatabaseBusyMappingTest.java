package com.flashbooking.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
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

    @Test
    void otherSqlStatesAndPlainErrorsAreNotBusy() {
        assertThat(GlobalExceptionHandler.isDatabaseBusy(new RuntimeException(new SQLException("u", "23505"))))
                .isFalse();
        assertThat(GlobalExceptionHandler.isDatabaseBusy(new IllegalStateException("boom"))).isFalse();
    }
}
