package dev.omarsolarte.awcentral;

import dev.omarsolarte.awcentral.device.TokenHasher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IngestIdempotencyTest {

    // Timescale, no Postgres vanilla: la migración usa create_hypertable.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired TestRestTemplate rest;
    @Autowired JdbcClient jdbc;

    private static final String TOKEN = "test-token-123";
    private static final String TOKEN_HASH = TokenHasher.sha256Hex(TOKEN);

    private String body(double duration) {
        return """
                {
                  "client_sent_at": "2026-09-28T14:03:11Z",
                  "tz": "America/Bogota",
                  "events": [
                    {"bucket_id": "aw-watcher-window_TEST", "source_event_id": 1,
                     "timestamp": "2026-09-28T13:58:00Z", "duration": %s,
                     "data": {"app": "Code.exe", "title_hash": "abc"}}
                  ]
                }
                """.formatted(duration);
    }

    private ResponseEntity<String> post(String token, String key, String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.setBearerAuth(token);
        if (key != null) h.set("Idempotency-Key", key);
        return rest.postForEntity("/v1/ingest", new HttpEntity<>(json, h), String.class);
    }

    @Test
    void sameBatchTwiceIsStoredOnce_andReplayedHeartbeatOnlyGrowsDuration() {
        UUID deviceId = UUID.randomUUID();
        jdbc.sql("INSERT INTO device (id, user_label, token_hash) VALUES (:id, 'tester', :h)")
                .param("id", deviceId).param("h", TOKEN_HASH).update();

        // sin token -> 401
        assertThat(post(null, "k0", body(10)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // primer envío
        ResponseEntity<String> r1 = post(TOKEN, "k1", body(10));
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r1.getBody()).contains("\"duplicate\":false");

        // mismo lote, misma clave -> duplicado, no procesa
        ResponseEntity<String> r2 = post(TOKEN, "k1", body(10));
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r2.getBody()).contains("\"duplicate\":true");

        // heartbeat reenviado con más duración, clave nueva -> upsert, sigue habiendo 1 evento
        ResponseEntity<String> r3 = post(TOKEN, "k2", body(42.5));
        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.OK);

        Integer count = jdbc.sql("SELECT count(*) FROM event WHERE device_id = :id")
                .param("id", deviceId).query(Integer.class).single();
        Double duration = jdbc.sql("SELECT duration_s FROM event WHERE device_id = :id")
                .param("id", deviceId).query(Double.class).single();
        assertThat(count).isEqualTo(1);
        assertThat(duration).isEqualTo(42.5);

        // duración menor reenviada no encoge el evento
        post(TOKEN, "k3", body(5));
        Double after = jdbc.sql("SELECT duration_s FROM event WHERE device_id = :id")
                .param("id", deviceId).query(Double.class).single();
        assertThat(after).isEqualTo(42.5);

        // el hash de Java coincide con el de Postgres (pgcrypto)
        String pgHash = jdbc.sql("SELECT encode(digest(:t, 'sha256'), 'hex')")
                .param("t", TOKEN).query(String.class).single();
        assertThat(pgHash).isEqualTo(TOKEN_HASH);
    }
}
