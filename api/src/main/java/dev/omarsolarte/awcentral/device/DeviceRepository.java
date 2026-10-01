package dev.omarsolarte.awcentral.device;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class DeviceRepository {

    private final JdbcClient jdbc;

    public DeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Device> findByTokenHash(String tokenHash) {
        return jdbc.sql("SELECT id, user_label, tz FROM device WHERE token_hash = :h")
                .param("h", tokenHash)
                .query((rs, i) -> new Device(
                        rs.getObject("id", UUID.class),
                        rs.getString("user_label"),
                        rs.getString("tz")))
                .optional();
    }

    public void touch(UUID deviceId, String tz) {
        jdbc.sql("UPDATE device SET last_seen_at = now(), tz = COALESCE(:tz, tz) WHERE id = :id")
                .param("tz", tz)
                .param("id", deviceId)
                .update();
    }
}
