package dev.omarsolarte.awcentral.ingest;

import dev.omarsolarte.awcentral.config.IngestProperties;
import dev.omarsolarte.awcentral.device.Device;
import dev.omarsolarte.awcentral.device.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final JdbcClient jdbc;
    private final EventRepository events;
    private final DeviceRepository devices;
    private final IngestProperties props;

    public IngestService(JdbcClient jdbc, EventRepository events, DeviceRepository devices, IngestProperties props) {
        this.jdbc = jdbc;
        this.events = events;
        this.devices = devices;
        this.props = props;
    }

    /**
     * Todo en una transacción: si el upsert falla, la clave de idempotencia no queda registrada
     * y el forwarder puede reintentar el mismo lote.
     */
    @Transactional
    public IngestResponse ingest(Device device, String idempotencyKey, IngestRequest req) {
        if (req.events().size() > props.maxBatchSize()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "max " + props.maxBatchSize() + " events per batch");
        }

        long skew = Duration.between(req.clientSentAt(), Instant.now()).getSeconds();
        if (Math.abs(skew) > props.skewWarnSeconds()) {
            log.warn("clock skew {}s for device {} ({})", skew, device.id(), device.userLabel());
        }

        int inserted = jdbc.sql("""
                        INSERT INTO ingest_batch (idempotency_key, device_id, event_count, client_sent_at, skew_seconds)
                        VALUES (:key, :device, :count, :sentAt, :skew)
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .param("key", idempotencyKey)
                .param("device", device.id())
                .param("count", req.events().size())
                .param("sentAt", OffsetDateTime.ofInstant(req.clientSentAt(), ZoneOffset.UTC))
                .param("skew", skew)
                .update();

        if (inserted == 0) {
            log.info("duplicate batch {} from device {}", idempotencyKey, device.id());
            return new IngestResponse(true, 0, skew);
        }

        events.upsertAll(device.id(), req.events());
        devices.touch(device.id(), req.tz());
        log.info("ingested {} events from device {} ({})", req.events().size(), device.id(), device.userLabel());
        return new IngestResponse(false, req.events().size(), skew);
    }
}
