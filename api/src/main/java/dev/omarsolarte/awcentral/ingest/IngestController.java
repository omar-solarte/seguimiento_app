package dev.omarsolarte.awcentral.ingest;

import dev.omarsolarte.awcentral.device.Device;
import dev.omarsolarte.awcentral.device.DeviceTokenFilter;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class IngestController {

    private final IngestService service;

    public IngestController(IngestService service) {
        this.service = service;
    }

    @PostMapping("/ingest")
    public IngestResponse ingest(@RequestAttribute(DeviceTokenFilter.DEVICE_ATTR) Device device,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @Valid @RequestBody IngestRequest request) {
        return service.ingest(device, idempotencyKey, request);
    }

    /** Política de minimización. Estática en el MVP; el forwarder la consulta al arrancar. */
    @GetMapping("/policy")
    public Map<String, Object> policy() {
        return Map.of(
                "send_titles", false,
                "bucket_prefixes", List.of("aw-watcher-window", "aw-watcher-afk"),
                "poll_seconds", 60,
                "replay_events", 20);
    }
}
