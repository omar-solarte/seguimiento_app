package dev.omarsolarte.awcentral.ingest;

public record IngestResponse(boolean duplicate, int received, long skewSeconds) {
}
