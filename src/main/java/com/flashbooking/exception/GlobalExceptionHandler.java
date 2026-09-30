package com.flashbooking.exception;

import java.net.URI;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.flashbooking.filter.CorrelationIdFilter;
import com.flashbooking.model.enums.ErrorCode;

import io.micrometer.core.instrument.MeterRegistry;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // lock timeout, statement timeout (query cancelled), deadlock, e o banco saindo/reiniciando:
    // 57P01 admin_shutdown, 57P02 crash_shutdown, 57P03 cannot_connect_now
    private static final Set<String> BUSY_SQL_STATES = Set.of("55P03", "57014", "40P01", "57P01", "57P02", "57P03");
    // classe 08: falha de conexao (08006 connection_failure, 08003 connection_does_not_exist, ...)
    private static final String CONNECTION_EXCEPTION_CLASS = "08";

    private final MeterRegistry meters;

    public GlobalExceptionHandler(MeterRegistry meters) {
        this.meters = meters;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusiness(BusinessException ex, HttpServletRequest req) {
        return build(ex.getCode(), ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBodyValidation(MethodArgumentNotValidException ex,
            HttpServletRequest req) {
        var fieldError = ex.getBindingResult().getFieldError();
        String field = fieldError != null ? fieldError.getField() : null;
        String message = fieldError != null ? fieldError.getDefaultMessage() : null;
        return build(codeForInvalidField(field), message, req);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex,
            HttpServletRequest req) {
        var violation = ex.getConstraintViolations().stream().findFirst().orElse(null);
        String path = violation != null ? violation.getPropertyPath().toString() : null;
        String message = violation != null ? violation.getMessage() : null;
        return build(codeForInvalidField(path), message, req);
    }

    /**
     * Extension point: maps a validated field name to its INVALID_* code. Unknown fields fall
     * back to MALFORMED_REQUEST.
     */
    static ErrorCode codeForInvalidField(String field) {
        if (field == null) {
            return ErrorCode.MALFORMED_REQUEST;
        }
        String name = field.substring(field.lastIndexOf('.') + 1);
        return switch (name) {
            case "quantity" -> ErrorCode.INVALID_QUANTITY;
            case "capacity" -> ErrorCode.INVALID_CAPACITY;
            case "name" -> ErrorCode.INVALID_EVENT_NAME;
            case "idempotencyKey" -> ErrorCode.INVALID_IDEMPOTENCY_KEY;
            default -> ErrorCode.MALFORMED_REQUEST;
        };
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex,
            HttpServletRequest req) {
        if ("Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())) {
            return build(ErrorCode.MISSING_IDEMPOTENCY_KEY, null, req);
        }
        return build(ErrorCode.MALFORMED_REQUEST, ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
            HttpServletRequest req) {
        if (UUID.class.equals(ex.getRequiredType())) {
            return build(ErrorCode.INVALID_ID_FORMAT, null, req);
        }
        return build(ErrorCode.MALFORMED_REQUEST, null, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex,
            HttpServletRequest req) {
        return build(ErrorCode.MALFORMED_REQUEST, null, req);
    }

    // Erros HTTP do proprio framework: sao do cliente (4xx), nunca 500; log curto, sem stack trace.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleRouteNotFound(NoResourceFoundException ex, HttpServletRequest req) {
        return clientError(ErrorCode.ROUTE_NOT_FOUND, null, req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
            HttpServletRequest req) {
        return clientError(ErrorCode.METHOD_NOT_ALLOWED, ex.getHeaders(), req); // header Allow
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
            HttpServletRequest req) {
        return clientError(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ex.getHeaders(), req); // header Accept
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex,
            HttpServletRequest req) {
        return clientError(ErrorCode.NOT_ACCEPTABLE, null, req);
    }

    private ResponseEntity<ProblemDetail> clientError(ErrorCode code, HttpHeaders headers, HttpServletRequest req) {
        log.info("request rejected status={} {} {}", code.status().value(), req.getMethod(), req.getRequestURI());
        return build(code, null, headers, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleAny(Exception ex, HttpServletRequest req) {
        if (isDatabaseBusy(ex)) {
            log.warn("database busy on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
            if (isReservationWrite(req)) { // GET /events e /reservations tambem dao 503, mas nao entram na metrica
                meters.counter("reservations.rejected", "reason", "db_busy").increment();
            }
            return build(ErrorCode.DATABASE_BUSY, null, req);
        }
        log.error("unexpected error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, null, req);
    }

    /** POST /events/{id}/reservations ou DELETE /reservations/{id}. */
    private static boolean isReservationWrite(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return ("POST".equals(req.getMethod()) && uri.startsWith("/events/") && uri.endsWith("/reservations"))
                || ("DELETE".equals(req.getMethod()) && uri.startsWith("/reservations/"));
    }

    static boolean isDatabaseBusy(Throwable ex) {
        for (Throwable t = ex; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof CannotGetJdbcConnectionException || t instanceof SQLTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null
                    && (BUSY_SQL_STATES.contains(sql.getSQLState())
                            || sql.getSQLState().startsWith(CONNECTION_EXCEPTION_CLASS))) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<ProblemDetail> build(ErrorCode code, String detail, HttpServletRequest req) {
        return build(code, detail, null, req);
    }

    private ResponseEntity<ProblemDetail> build(ErrorCode code, String detail, HttpHeaders extraHeaders,
            HttpServletRequest req) {
        HttpStatus status = code.status();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail != null ? detail : code.defaultMessage());
        pd.setTitle(status.getReasonPhrase());
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", code.name());
        pd.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));

        // Content-Type fixo: a resposta de erro nao depende do Accept do cliente (evita 406 dentro do tratamento de erro)
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (extraHeaders != null) {
            response = response.headers(extraHeaders);
        }
        if (code == ErrorCode.DATABASE_BUSY) {
            response = response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(pd);
    }
}
