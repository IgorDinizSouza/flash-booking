package com.flashbooking.infra;

import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.flashbooking.filter.CorrelationIdFilter;

/** Origem unica de correlationId (MDC) e instanceId (env INSTANCE_ID) para o historico. */
@Component
public class AuditContext {

    private final String instanceId;

    /** reservation_history.instance_id e VARCHAR(30). */
    public static final int MAX_INSTANCE_ID_LENGTH = 30;

    private static final Pattern SAFE_INSTANCE_ID = Pattern.compile("[A-Za-z0-9._-]{1," + MAX_INSTANCE_ID_LENGTH + "}");

    /** Fail-fast: um INSTANCE_ID invalido quebraria todas as escritas do historico em runtime. */
    public AuditContext(@Value("${INSTANCE_ID:local}") String instanceId) {
        if (instanceId == null || !SAFE_INSTANCE_ID.matcher(instanceId).matches()) {
            throw new IllegalStateException("Invalid INSTANCE_ID '" + instanceId + "': it must have 1 to "
                    + MAX_INSTANCE_ID_LENGTH + " characters from [A-Za-z0-9._-] (reservation_history.instance_id is "
                    + "VARCHAR(" + MAX_INSTANCE_ID_LENGTH + "))");
        }
        this.instanceId = instanceId;
    }

    public String correlationId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }

    public String instanceId() {
        return instanceId;
    }
}
