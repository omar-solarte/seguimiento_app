package dev.omarsolarte.awcentral.ingest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.Map;

public record IngestEvent(
        @NotBlank String bucketId,
        @NotNull Long sourceEventId,
        @NotNull Instant timestamp,
        @NotNull @PositiveOrZero Double duration,
        @NotNull Map<String, Object> data) {
}
