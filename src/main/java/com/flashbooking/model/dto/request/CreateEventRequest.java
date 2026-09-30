package com.flashbooking.model.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

public record CreateEventRequest(
        @Schema(example = "Java Festival")
        @NotBlank @Size(max = 150) String name,
        @Schema(example = "50")
        @NotNull @Min(1) @Max(1_000_000) Integer capacity) {
}
