package com.flashbooking.service;

import java.util.UUID;

import com.flashbooking.model.dto.request.CreateEventRequest;
import com.flashbooking.model.dto.response.EventResponse;

public interface IEventService {

    EventResponse create(CreateEventRequest request);

    /** Leitura com cache local de TTL curto: a disponibilidade pode estar defasada. */
    EventResponse getById(UUID id);
}
