package com.flashbooking.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.flashbooking.config.BookingProperties;

/**
 * Aplica os timeouts de lock/statement a transacao corrente. Deve ser a primeira chamada de
 * todo metodo {@code @Transactional} de escrita: SET LOCAL so vale dentro da transacao.
 */
@Component
public class TxSupport {

    private final JdbcClient jdbc;
    private final long lockTimeoutMs;
    private final long statementTimeoutMs;

    public TxSupport(JdbcClient jdbc, BookingProperties props) {
        this.jdbc = jdbc;
        this.lockTimeoutMs = props.db().lockTimeout().toMillis();
        this.statementTimeoutMs = props.db().statementTimeout().toMillis();
    }

    public void applyTimeouts() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TxSupport.applyTimeouts() requires an active transaction");
        }
        // set_config(..., true) equivale a SET LOCAL, mas aceita parametros
        jdbc.sql("SELECT set_config('lock_timeout', :lock, true), set_config('statement_timeout', :stmt, true)")
                .param("lock", lockTimeoutMs + "ms")
                .param("stmt", statementTimeoutMs + "ms")
                .query().singleRow();
    }
}
