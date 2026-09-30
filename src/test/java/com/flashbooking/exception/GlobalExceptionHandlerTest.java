package com.flashbooking.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.flashbooking.model.enums.ErrorCode;
import com.flashbooking.support.AbstractIntegrationTest;

import java.util.Map;
import java.util.UUID;

@Import(GlobalExceptionHandlerTest.TestControllers.class)
class GlobalExceptionHandlerTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class TestControllers {
        @RestController
        static class Probe {
            @GetMapping("/test/business")
            void business() {
                throw new BusinessException(ErrorCode.EVENT_NOT_FOUND);
            }

            @GetMapping("/test/uuid/{id}")
            void uuid(@PathVariable UUID id) {
            }

            @PostMapping("/test/body")
            void body(@RequestBody Map<String, Object> body) {
            }

            @PostMapping("/test/header")
            void header(@RequestHeader("Idempotency-Key") String key) {
            }

            @GetMapping("/test/lock-timeout")
            void lockTimeout() {
                throw new UncategorizedSQLException("task", "sql",
                        new SQLException("canceling statement due to lock timeout", "55P03"));
            }

            @GetMapping("/test/admin-shutdown")
            void adminShutdown() {
                throw new UncategorizedSQLException("task", "sql",
                        new SQLException("terminating connection due to administrator command", "57P01"));
            }

            @GetMapping("/test/boom")
            void boom() {
                throw new IllegalStateException("secret internal detail");
            }
        }
    }

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<JsonNode> get(String path, String correlationId) {
        var headers = new HttpHeaders();
        if (correlationId != null) {
            headers.set("X-Correlation-Id", correlationId);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    @Test
    void businessExceptionBecomesProblemDetailWithCodeAndCorrelationId() {
        var res = get("/test/business", "corr-123");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-123");
        assertThat(res.getHeaders().getFirst("X-Instance-Id")).isEqualTo("local");
        JsonNode body = res.getBody();
        assertThat(body.get("code").asText()).isEqualTo("EVENT_NOT_FOUND");
        assertThat(body.get("correlationId").asText()).isEqualTo("corr-123");
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.get("instance").asText()).isEqualTo("/test/business");
        assertThat(body.get("detail").asText()).isNotBlank();
    }

    @Test
    void correlationIdIsGeneratedWhenAbsent() {
        var res = get("/test/business", null);
        String header = res.getHeaders().getFirst("X-Correlation-Id");
        assertThat(header).isNotBlank();
        assertThat(res.getBody().get("correlationId").asText()).isEqualTo(header);
    }

    @Test
    void malformedUuidIsInvalidIdFormat() {
        var res = get("/test/uuid/not-a-uuid", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INVALID_ID_FORMAT");
    }

    @Test
    void malformedJsonIsMalformedRequest() {
        var headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var res = rest.exchange("/test/body", HttpMethod.POST, new HttpEntity<>("{oops", headers), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void missingIdempotencyKeyHeader() {
        var res = rest.exchange("/test/header", HttpMethod.POST, new HttpEntity<>(new HttpHeaders()), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().get("code").asText()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    void lockTimeoutSqlStateBecomes503WithRetryAfter() {
        var res = get("/test/lock-timeout", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(res.getBody().get("code").asText()).isEqualTo("DATABASE_BUSY");
    }

    @Test
    void unexpectedErrorIs500WithoutLeakingDetails() {
        var res = get("/test/boom", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(res.getBody().toString()).doesNotContain("secret internal detail").doesNotContain("trace");
    }

    @Test
    void adminShutdownSqlStateBecomes503WithRetryAfter() {
        var res = get("/test/admin-shutdown", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(res.getBody().get("code").asText()).isEqualTo("DATABASE_BUSY");
    }
}
