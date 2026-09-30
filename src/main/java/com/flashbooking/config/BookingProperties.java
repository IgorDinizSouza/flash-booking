package com.flashbooking.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "booking")
public record BookingProperties(
        @DefaultValue Reservation reservation,
        @DefaultValue AvailabilityCache availabilityCache,
        @DefaultValue Db db) {

    public record Reservation(
            @DefaultValue("10m") Duration ttl,
            @DefaultValue("10") int maxQuantity,
            @DefaultValue("true") boolean expirationJobEnabled,
            @DefaultValue("5s") Duration expirationJobDelay,
            @DefaultValue("100") int expirationBatchSize) {
    }

    public record AvailabilityCache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1s") Duration ttl) {
    }

    public record Db(
            @DefaultValue("1s") Duration lockTimeout,
            @DefaultValue("3s") Duration statementTimeout) {
    }
}
