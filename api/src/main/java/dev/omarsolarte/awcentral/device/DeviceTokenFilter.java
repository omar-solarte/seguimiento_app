package dev.omarsolarte.awcentral.device;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Autenticación mínima por token de dispositivo (Bearer). Sin Spring Security a propósito:
 * una tabla, un hash, un filtro. /actuator queda público para el health check del proxy.
 */
@Component
public class DeviceTokenFilter extends OncePerRequestFilter {

    public static final String DEVICE_ATTR = "device";

    private final DeviceRepository devices;

    public DeviceTokenFilter(DeviceRepository devices) {
        this.devices = devices;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
            reject(response);
            return;
        }
        String token = header.substring(7).trim();
        Optional<Device> device = devices.findByTokenHash(TokenHasher.sha256Hex(token));
        if (device.isEmpty()) {
            reject(response);
            return;
        }
        request.setAttribute(DEVICE_ATTR, device.get());
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}
