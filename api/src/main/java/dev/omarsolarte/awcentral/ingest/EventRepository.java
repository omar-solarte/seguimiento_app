package dev.omarsolarte.awcentral.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class EventRepository {

    private static final String UPSERT = """
            INSERT INTO event (device_id, bucket_id, source_event_id, start_ts, duration_s, data)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (device_id, bucket_id, source_event_id, start_ts)
            DO UPDATE SET duration_s = GREATEST(event.duration_s, EXCLUDED.duration_s),
                          data = EXCLUDED.data,
                          received_at = now()
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EventRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Upsert en lote. Un heartbeat reenviado con mayor duración solo alarga el evento existente. */
    public void upsertAll(UUID deviceId, List<IngestEvent> events) {
        jdbc.batchUpdate(UPSERT, events, 500, (ps, e) -> {
            ps.setObject(1, deviceId);
            ps.setString(2, e.bucketId());
            ps.setLong(3, e.sourceEventId());
            ps.setObject(4, OffsetDateTime.ofInstant(e.timestamp(), ZoneOffset.UTC));
            ps.setDouble(5, e.duration());
            ps.setString(6, toJson(e.data()));
        });
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
