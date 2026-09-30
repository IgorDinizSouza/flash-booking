package com.flashbooking.service.impl;

import java.util.UUID;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.flashbooking.config.CacheConfig;
import com.flashbooking.exception.BusinessException;
import com.flashbooking.model.domain.Event;
import com.flashbooking.model.dto.request.CreateEventRequest;
import com.flashbooking.model.dto.response.EventResponse;
import com.flashbooking.model.enums.ErrorCode;
import com.flashbooking.repository.EventRepository;
import com.flashbooking.service.IEventService;

@Service
public class EventService implements IEventService {

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public EventResponse create(CreateEventRequest request) {
        Event event = eventRepository.insert(request.name().trim(), request.capacity());
        return EventResponse.from(event);
    }

    @Override
    @Cacheable(CacheConfig.EVENTS_CACHE)
    public EventResponse getById(UUID id) {
        return eventRepository.findById(id)
                .map(EventResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
    }
}
