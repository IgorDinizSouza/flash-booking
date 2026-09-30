package com.flashbooking.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String EVENTS_CACHE = "events";

    @Bean
    public CacheManager cacheManager(BookingProperties props) {
        var cacheProps = props.availabilityCache();
        if (!cacheProps.enabled()) {
            return new NoOpCacheManager();
        }
        var manager = new CaffeineCacheManager(EVENTS_CACHE);
        manager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(cacheProps.ttl()).maximumSize(10_000));
        return manager;
    }
}
