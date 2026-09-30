package com.flashbooking.model.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.flashbooking.model.domain.Event;

public record EventResponse(UUID id, String name, int capacity, int available, Instant createdAt) {

    public static EventResponse from(Event event) {
        return new EventResponse(event.id(), event.name(), event.totalCapacity(), event.available(),
                event.createdAt().toInstant());
    }
}
