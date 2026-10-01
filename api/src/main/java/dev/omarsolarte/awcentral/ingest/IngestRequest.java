package dev.omarsolarte.awcentral.ingest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public record IngestRequest(
        @NotNull Instant clientSentAt,
        String tz,
        @NotEmpty @Valid List<IngestEvent> events) {
}
