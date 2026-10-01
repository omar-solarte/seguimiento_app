-- Esquema MVP. Requiere la imagen timescale/timescaledb (Postgres vanilla no tiene create_hypertable).
CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE device (
  id            UUID PRIMARY KEY,
  user_label    TEXT NOT NULL,          -- identidad humana en el MVP (sin IdP)
  hostname      TEXT,                   -- solo etiqueta, nunca identidad
  token_hash    TEXT NOT NULL UNIQUE,   -- sha256 hex del token; el token nunca se guarda
  tz            TEXT,                   -- zona IANA reportada por el forwarder
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen_at  TIMESTAMPTZ
);

CREATE TABLE event (
  device_id        UUID NOT NULL REFERENCES device(id),
  bucket_id        TEXT NOT NULL,
  source_event_id  BIGINT NOT NULL,     -- id del evento en el aw-server local
  start_ts         TIMESTAMPTZ NOT NULL,
  duration_s       DOUBLE PRECISION NOT NULL,
  data             JSONB NOT NULL,      -- ya minimizado por el forwarder
  received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (device_id, bucket_id, source_event_id, start_ts)
);
SELECT create_hypertable('event', 'start_ts');
CREATE INDEX event_device_start_idx ON event (device_id, start_ts DESC);

CREATE TABLE ingest_batch (
  idempotency_key  TEXT PRIMARY KEY,
  device_id        UUID NOT NULL REFERENCES device(id),
  event_count      INT NOT NULL,
  client_sent_at   TIMESTAMPTZ NOT NULL,
  skew_seconds     BIGINT NOT NULL,
  received_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Horas por usuario, día (en la zona del dispositivo) y aplicación
CREATE VIEW v_daily_app AS
SELECT d.user_label,
       (e.start_ts AT TIME ZONE COALESCE(d.tz, 'UTC'))::date AS day,
       e.data->>'app'                                          AS app,
       SUM(e.duration_s) / 3600.0                              AS hours
FROM event e
JOIN device d ON d.id = e.device_id
WHERE e.bucket_id LIKE 'aw-watcher-window%'
GROUP BY 1, 2, 3;

-- Horas activas vs AFK por usuario y día
CREATE VIEW v_daily_afk AS
SELECT d.user_label,
       (e.start_ts AT TIME ZONE COALESCE(d.tz, 'UTC'))::date AS day,
       e.data->>'status'                                       AS status,
       SUM(e.duration_s) / 3600.0                              AS hours
FROM event e
JOIN device d ON d.id = e.device_id
WHERE e.bucket_id LIKE 'aw-watcher-afk%'
GROUP BY 1, 2, 3;
