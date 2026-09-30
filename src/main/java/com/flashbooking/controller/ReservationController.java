package com.flashbooking.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.flashbooking.model.dto.request.CreateReservationRequest;
import com.flashbooking.model.dto.response.ReservationResponse;
import com.flashbooking.service.IReservationService;
import com.flashbooking.service.impl.ReservationResult;

@RestController
@Tag(name = "Reservations")
public class ReservationController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final IReservationService reservationService;

    public ReservationController(IReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/events/{id}/reservations")
    @Operation(summary = "Reserve tickets",
            description = "Idempotent by the Idempotency-Key header (1-150 chars). Repeating the same key with the "
                    + "same request returns the same status and body with Idempotent-Replayed: true. A business "
                    + "error (e.g. 409 INSUFFICIENT_CAPACITY) does not retain the key.")
    @ApiResponse(responseCode = "201", description = "Reservation created (or replayed)",
            headers = {
                @Header(name = "Location", description = "/reservations/{id}"),
                @Header(name = REPLAYED, description = "true when the response is a replay")},
            content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
                    {"id":"98ac1c3e-0000-4000-8000-000000000001","eventId":"57d8a1c2-0000-4000-8000-000000000000",
                     "quantity":2,"status":"PENDING","expiresAt":"2026-09-30T14:10:00Z",
                     "createdAt":"2026-09-30T14:00:00Z"}""")))
    @ApiResponse(responseCode = "400",
            description = "Missing/invalid Idempotency-Key, invalid quantity, malformed id or body",
            content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(value = """
                    {"type":"about:blank","title":"Bad Request","status":400,
                     "detail":"Header Idempotency-Key is required",
                     "instance":"/events/57d8a1c2-0000-4000-8000-000000000000/reservations",
                     "code":"MISSING_IDEMPOTENCY_KEY","correlationId":"6f1c0d0e-9a52-4a0e-bb5c-1f3a2a7d9c11"}""")))
    @ApiResponse(responseCode = "404", description = "Event not found",
            content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(value = """
                    {"type":"about:blank","title":"Not Found","status":404,
                     "detail":"Event not found",
                     "instance":"/events/57d8a1c2-0000-4000-8000-000000000000/reservations",
                     "code":"EVENT_NOT_FOUND","correlationId":"6f1c0d0e-9a52-4a0e-bb5c-1f3a2a7d9c11"}""")))
    @ApiResponse(responseCode = "409", description = "INSUFFICIENT_CAPACITY or IDEMPOTENCY_KEY_CONFLICT",
            content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(value = """
                    {"type":"about:blank","title":"Conflict","status":409,
                     "detail":"Not enough tickets available",
                     "instance":"/events/57d8a1c2-0000-4000-8000-000000000000/reservations",
                     "code":"INSUFFICIENT_CAPACITY","correlationId":"6f1c0d0e-9a52-4a0e-bb5c-1f3a2a7d9c11"}""")))
    @ApiResponse(responseCode = "503",
            description = "DATABASE_BUSY (lock/statement timeout, pool exhausted); retry with the same key")
    public ResponseEntity<ReservationResponse> create(@PathVariable("id") UUID eventId,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody CreateReservationRequest request) {
        ReservationResult result = reservationService.create(eventId, idempotencyKey, request);
        var response = ResponseEntity.status(HttpStatusCode.valueOf(result.httpStatus()))
                .location(URI.create("/reservations/" + result.body().id()));
        if (result.replayed()) {
            response = response.header(REPLAYED, "true");
        }
        return response.body(result.body());
    }

    @GetMapping("/reservations/{id}")
    @Operation(summary = "Get a reservation",
            description = "Effective status: a PENDING reservation past expiresAt (database clock) is reported as "
                    + "EXPIRED without side effects; stock is returned later by the expiration job.")
    @ApiResponse(responseCode = "200", description = "Reservation found")
    @ApiResponse(responseCode = "400", description = "Malformed UUID")
    @ApiResponse(responseCode = "404", description = "Reservation not found",
            content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(value = """
                    {"type":"about:blank","title":"Not Found","status":404,
                     "detail":"Reservation not found","instance":"/reservations/98ac1c3e-0000-4000-8000-000000000001",
                     "code":"RESERVATION_NOT_FOUND","correlationId":"6f1c0d0e-9a52-4a0e-bb5c-1f3a2a7d9c11"}""")))
    public ReservationResponse get(@PathVariable UUID id) {
        return reservationService.getById(id);
    }

    @DeleteMapping("/reservations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cancel a reservation",
            description = "Cancels a PENDING, not yet expired reservation and returns its stock. Repeating the "
                    + "call on an already CANCELLED reservation also returns 204 without returning stock again.")
    @ApiResponse(responseCode = "204", description = "Cancelled (or already cancelled)")
    @ApiResponse(responseCode = "400", description = "Malformed UUID (INVALID_ID_FORMAT)")
    @ApiResponse(responseCode = "404", description = "Reservation not found",
            content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(value = """
                    {"type":"about:blank","title":"Not Found","status":404,
                     "detail":"Reservation not found","instance":"/reservations/98ac1c3e-0000-4000-8000-000000000001",
                     "code":"RESERVATION_NOT_FOUND","correlationId":"6f1c0d0e-9a52-4a0e-bb5c-1f3a2a7d9c11"}""")))
    @ApiResponse(responseCode = "409", description = "EXPIRED, or PENDING already past expiresAt",
            content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(value = """
                    {"type":"about:blank","title":"Conflict","status":409,
                     "detail":"Reservation is not in a state that allows this operation",
                     "instance":"/reservations/98ac1c3e-0000-4000-8000-000000000001",
                     "code":"INVALID_RESERVATION_STATE","correlationId":"6f1c0d0e-9a52-4a0e-bb5c-1f3a2a7d9c11"}""")))
    public void cancel(@PathVariable UUID id) {
        reservationService.cancel(id);
    }
}
