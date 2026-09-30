package com.flashbooking.model.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Event(UUID id, String name, int totalCapacity, int available, OffsetDateTime createdAt) {
}
