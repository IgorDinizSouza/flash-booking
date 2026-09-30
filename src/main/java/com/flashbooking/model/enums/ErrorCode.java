package com.flashbooking.model.enums;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    MISSING_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Header Idempotency-Key is required"),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Idempotency-Key must have between 1 and 150 characters"),
    INVALID_QUANTITY(HttpStatus.BAD_REQUEST, "Quantity is out of the allowed range"),
    INVALID_CAPACITY(HttpStatus.BAD_REQUEST, "Capacity must be between 1 and 1000000"),
    INVALID_EVENT_NAME(HttpStatus.BAD_REQUEST, "Event name must be non-blank and have at most 150 characters"),
    INVALID_ID_FORMAT(HttpStatus.BAD_REQUEST, "Malformed identifier: a valid UUID is expected"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body"),
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Event not found"),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Reservation not found"),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "Route not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported for this route"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type is not supported; use application/json"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not able to produce a representation acceptable to the client; use application/json"),
    INSUFFICIENT_CAPACITY(HttpStatus.CONFLICT, "Not enough tickets available"),
    INVALID_RESERVATION_STATE(HttpStatus.CONFLICT, "Reservation is not in a state that allows this operation"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "Idempotency-Key was already used with a different request"),
    DATABASE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Service is busy, please retry shortly"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected internal error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
