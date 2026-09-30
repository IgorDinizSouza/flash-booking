package com.flashbooking.filter;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.flashbooking.infra.AuditContext;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class InstanceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Instance-Id";

    private final String instanceId;

    public InstanceIdFilter(AuditContext audit) {
        this.instanceId = audit.instanceId(); // ja validado (fail-fast) no AuditContext
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, instanceId);
        chain.doFilter(request, response);
    }
}
