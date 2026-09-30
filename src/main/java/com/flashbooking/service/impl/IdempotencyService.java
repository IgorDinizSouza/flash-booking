package com.flashbooking.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.flashbooking.exception.BusinessException;
import com.flashbooking.model.domain.IdempotencyKey;
import com.flashbooking.model.enums.ErrorCode;
import com.flashbooking.repository.IdempotencyRepository;
import com.flashbooking.service.IIdempotencyService;

@Service
public class IdempotencyService implements IIdempotencyService {

    private final IdempotencyRepository repository;

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
    }

    @Override
    public String requestHash(UUID eventId, int quantity) {
        String canonical = "POST|/events/" + eventId.toString().toLowerCase(Locale.ROOT) + "/reservations|"
                + "{\"quantity\":" + quantity + "}";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<IdempotencyKey> begin(String key, String requestHash) {
        if (repository.tryInsert(key, requestHash)) {
            return Optional.empty();
        }
        // o INSERT esperou a transacao concorrente terminar: a linha existente esta commitada
        IdempotencyKey existing = repository.find(key)
                .orElseThrow(() -> new IllegalStateException("idempotency key vanished after conflict: " + key));
        if (!existing.requestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        if (existing.httpStatus() == null || existing.responseJson() == null) {
            throw new IllegalStateException("idempotency key committed without a stored response: " + key);
        }
        return Optional.of(existing);
    }

    @Override
    public void complete(String key, int httpStatus, String responseJson) {
        repository.complete(key, httpStatus, responseJson);
    }
}
