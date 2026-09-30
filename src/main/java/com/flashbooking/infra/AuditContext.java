package com.flashbooking.infra;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.flashbooking.filter.CorrelationIdFilter;

/** Origem unica de correlationId (MDC) e instanceId (env INSTANCE_ID) para o historico. */
@Component
public class AuditContext {

    private final String instanceId;

    public AuditContext(@Value("${INSTANCE_ID:local}") String instanceId) {
        this.instanceId = instanceId;
    }

    public String correlationId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }

    public String instanceId() {
        return instanceId;
    }
}
