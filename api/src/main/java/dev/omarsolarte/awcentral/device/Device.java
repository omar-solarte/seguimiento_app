package dev.omarsolarte.awcentral.device;

import java.util.UUID;

public record Device(UUID id, String userLabel, String tz) {
}
