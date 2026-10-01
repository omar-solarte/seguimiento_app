package dev.omarsolarte.awcentral.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(int maxBatchSize, int skewWarnSeconds) {
}
