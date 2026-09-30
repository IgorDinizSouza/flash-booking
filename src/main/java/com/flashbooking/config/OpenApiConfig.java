package com.flashbooking.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI flashBookingOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Flash Booking API")
                .version("v1")
                .description("Event ticket reservations with zero oversell, idempotency and automatic expiration."));
    }
}
