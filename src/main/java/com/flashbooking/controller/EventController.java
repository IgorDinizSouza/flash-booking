package com.flashbooking.controller;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.flashbooking.model.dto.request.CreateEventRequest;
import com.flashbooking.model.dto.response.EventResponse;
import com.flashbooking.service.IEventService;

@RestController
@RequestMapping("/events")
@Tag(name = "Events")
public class EventController {

    private final IEventService eventService;

    public EventController(IEventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    @Operation(summary = "Create an event", description = "Creates an event with all tickets available.")
    @ApiResponse(responseCode = "201", description = "Event created")
    @ApiResponse(responseCode = "400", description = "Invalid name, capacity or malformed body",
            content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(value = """
                    {"type":"about:blank","title":"Bad Request","status":400,
                     "detail":"Capacity must be between 1 and 1000000","instance":"/events",
                     "code":"INVALID_CAPACITY","correlationId":"6f1c0d0e-9a52-4a0e-bb5c-1f3a2a7d9c11"}""")))
    public ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request) {
        EventResponse created = eventService.create(request);
        return ResponseEntity.created(URI.create("/events/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an event",
            description = "Returns the event. Availability may be slightly stale (short-TTL local cache).")
    @ApiResponse(responseCode = "200", description = "Event found")
    @ApiResponse(responseCode = "400", description = "Malformed UUID",
            content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(value = """
                    {"type":"about:blank","title":"Bad Request","status":400,
                     "detail":"Malformed identifier: a valid UUID is expected","instance":"/events/abc",
                     "code":"INVALID_ID_FORMAT","correlationId":"6f1c0d0e-9a52-4a0e-bb5c-1f3a2a7d9c11"}""")))
    @ApiResponse(responseCode = "404", description = "Event not found",
            content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(value = """
                    {"type":"about:blank","title":"Not Found","status":404,
                     "detail":"Event not found","instance":"/events/57d8a1c2-0000-4000-8000-000000000000",
                     "code":"EVENT_NOT_FOUND","correlationId":"6f1c0d0e-9a52-4a0e-bb5c-1f3a2a7d9c11"}""")))
    public EventResponse get(@PathVariable UUID id) {
        return eventService.getById(id);
    }
}
