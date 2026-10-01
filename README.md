# aw-central (MVP)

Centralización de eventos de ActivityWatch para equipos remotos.

- `api/`        API de ingesta (Java 21, Spring Boot 3, Gradle, TimescaleDB, Flyway)
- `forwarder/`  Agente por equipo (Python 3.11+) que lee el aw-server local y envía a la API
- `deploy/`     Docker Compose para el VPS (TimescaleDB + API + Grafana + Caddy con TLS)
- `scripts/`    Alta de dispositivos y consultas para Grafana

El paso a paso con criterios de verificación está en la conversación donde se generó este proyecto.
