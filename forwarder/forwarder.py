#!/usr/bin/env python3
"""
Forwarder MVP: lee eventos del aw-server local (127.0.0.1:5600), los minimiza y los
envía por HTTPS a aw-central-api con un token de dispositivo.

Diseño:
- La base local de ActivityWatch es el buffer: si el envío falla, no se avanza el cursor.
- El último evento de cada bucket crece mientras el usuario sigue en la misma ventana
  (heartbeats), por eso se reenvían los últimos N eventos; el servidor hace upsert.
- Si el equipo estuvo apagado/offline, el cursor guarda el timestamp del último evento
  enviado y se lee desde ahí en ventanas de `window_hours`.
"""
import hashlib
import json
import logging
import os
import sys
import time
import tomllib
import uuid
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

import requests

LOCAL_API = "http://127.0.0.1:5600/api/0"
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "forwarder.toml")
CURSOR_PATH = os.path.join(BASE_DIR, "cursor.json")

log = logging.getLogger("forwarder")


# ---------- utilidades ----------

def load_config() -> dict:
    with open(CONFIG_PATH, "rb") as f:
        cfg = tomllib.load(f)
    cfg.setdefault("poll_seconds", 60)
    cfg.setdefault("lookback_minutes", 30)
    cfg.setdefault("backfill_hours", 24)
    cfg.setdefault("window_hours", 6)
    cfg.setdefault("replay_events", 20)
    cfg.setdefault("max_batch", 2000)
    cfg.setdefault("send_titles", False)
    cfg.setdefault("bucket_prefixes", ["aw-watcher-window", "aw-watcher-afk", "aw-watcher-web"])
    cfg.setdefault("tz", "")
    if not cfg.get("server_url") or not cfg.get("token"):
        sys.exit("forwarder.toml debe tener server_url y token")
    return cfg


def load_cursor() -> dict:
    if not os.path.exists(CURSOR_PATH):
        return {}
    with open(CURSOR_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


def save_cursor(cursor: dict) -> None:
    tmp = CURSOR_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cursor, f, indent=2)
    os.replace(tmp, CURSOR_PATH)


def local_tz(cfg: dict) -> str:
    if cfg.get("tz"):
        return cfg["tz"]
    try:
        from tzlocal import get_localzone_name  # opcional
        return get_localzone_name()
    except Exception:
        return "UTC"


def to_utc_z(ts: str) -> str:
    """AW devuelve ISO con offset (+00:00). Normalizamos a Z para el servidor."""
    dt = datetime.fromisoformat(ts.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def sha16(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


# ---------- minimización ----------

def domain_of(url: str) -> str:
    """Solo el dominio. Nunca ruta, parámetros ni credenciales de la URL."""
    try:
        p = urlparse(url or "")
        host = p.hostname
    except ValueError:
        return "(invalida)"
    if p.scheme not in ("http", "https") or not host:
        return "(interno)"  # chrome://, about:blank, nueva pestaña, etc.
    host = host.lower()
    return host[4:] if host.startswith("www.") else host


def minimize(bucket_id: str, event: dict, send_titles: bool):
    """Devuelve el evento minimizado, o None si no debe salir del equipo."""
    data = event.get("data") or {}
    if bucket_id.startswith("aw-watcher-window"):
        out = {"app": data.get("app")}
        title = data.get("title") or ""
        if send_titles:
            out["title"] = title
        else:
            out["title_hash"] = sha16(title)
    elif bucket_id.startswith("aw-watcher-afk"):
        out = {"status": data.get("status")}
    elif bucket_id.startswith("aw-watcher-web"):
        if data.get("incognito"):
            return None  # incógnito: nada sale del equipo
        out = {"domain": domain_of(data.get("url")), "audible": bool(data.get("audible"))}
        if send_titles:
            out["title"] = data.get("title") or ""
    else:
        return None  # buckets desconocidos no se envían
    return {
        "bucket_id": bucket_id,
        "source_event_id": int(event["id"]),
        "timestamp": to_utc_z(event["timestamp"]),
        "duration": float(event.get("duration") or 0.0),
        "data": out,
    }


# ---------- lectura local ----------

def fetch_events(session: requests.Session, bucket_id: str, start: datetime, end: datetime) -> list:
    r = session.get(
        f"{LOCAL_API}/buckets/{bucket_id}/events",
        params={"start": iso(start), "end": iso(end), "limit": 5000},
        timeout=15,
    )
    r.raise_for_status()
    events = r.json()
    if len(events) >= 5000:
        log.warning("bucket %s: ventana %s..%s devolvió 5000 eventos; posible truncado, reduce window_hours",
                    bucket_id, iso(start), iso(end))
    return events


# ---------- envío ----------

def post_batch(session: requests.Session, cfg: dict, tz: str, events: list) -> dict:
    payload = {
        "client_sent_at": iso(datetime.now(timezone.utc)),
        "tz": tz,
        "events": events,
    }
    r = session.post(
        cfg["server_url"].rstrip("/") + "/v1/ingest",
        json=payload,
        headers={
            "Authorization": f"Bearer {cfg['token']}",
            "Idempotency-Key": str(uuid.uuid4()),
        },
        timeout=30,
    )
    r.raise_for_status()
    return r.json()


# ---------- ciclo ----------

def process_bucket(local: requests.Session, remote: requests.Session, cfg: dict, tz: str,
                   bucket_id: str, cursor: dict) -> None:
    now = datetime.now(timezone.utc)
    state = cursor.get(bucket_id) or {}
    last_id = int(state.get("last_id", 0))

    if state.get("last_ts"):
        last_ts = datetime.fromisoformat(state["last_ts"].replace("Z", "+00:00"))
        start = min(now - timedelta(minutes=cfg["lookback_minutes"]), last_ts - timedelta(minutes=5))
    else:
        start = now - timedelta(hours=cfg["backfill_hours"])

    window = timedelta(hours=cfg["window_hours"])
    collected: dict[int, dict] = {}
    w0 = start
    while w0 < now:
        w1 = min(w0 + window, now + timedelta(minutes=1))
        for e in fetch_events(local, bucket_id, w0, w1):
            collected[int(e["id"])] = e
        w0 = w1

    if not collected:
        return

    # reenvía los últimos N por si crecieron (heartbeats); el resto solo si son nuevos
    ids_sorted = sorted(collected)
    threshold = last_id - cfg["replay_events"]
    selected = [collected[i] for i in ids_sorted if i > threshold]
    if not selected:
        return

    payload = [m for m in (minimize(bucket_id, e, cfg["send_titles"]) for e in selected) if m]
    newest = collected[ids_sorted[-1]]
    if not payload:
        # todo se descartó (p. ej. solo incógnito): avanza el cursor sin enviar
        cursor[bucket_id] = {"last_id": ids_sorted[-1], "last_ts": to_utc_z(newest["timestamp"])}
        save_cursor(cursor)
        return
    sent = 0
    for i in range(0, len(payload), cfg["max_batch"]):
        chunk = payload[i:i + cfg["max_batch"]]
        resp = post_batch(remote, cfg, tz, chunk)
        sent += len(chunk)
        if abs(resp.get("skew_seconds", 0)) > 120:
            log.warning("skew de reloj reportado por el servidor: %ss", resp.get("skew_seconds"))

    cursor[bucket_id] = {"last_id": ids_sorted[-1], "last_ts": to_utc_z(newest["timestamp"])}
    save_cursor(cursor)
    log.info("bucket %s: %d eventos enviados (last_id=%d)", bucket_id, sent, ids_sorted[-1])


def run_once(local: requests.Session, remote: requests.Session, cfg: dict, tz: str, cursor: dict) -> None:
    r = local.get(f"{LOCAL_API}/buckets/", timeout=10)
    r.raise_for_status()
    buckets = r.json()  # dict {bucket_id: metadata}
    for bucket_id in buckets:
        if not any(bucket_id.startswith(p) for p in cfg["bucket_prefixes"]):
            continue
        process_bucket(local, remote, cfg, tz, bucket_id, cursor)


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    cfg = load_config()
    tz = local_tz(cfg)
    cursor = load_cursor()
    local, remote = requests.Session(), requests.Session()
    once = "--once" in sys.argv
    log.info("forwarder iniciado; servidor=%s tz=%s titles=%s", cfg["server_url"], tz, cfg["send_titles"])
    while True:
        try:
            run_once(local, remote, cfg, tz, cursor)
        except requests.RequestException as ex:
            # no se avanza el cursor: se reintenta en el próximo ciclo
            log.warning("ciclo fallido, se reintenta: %s", ex)
        except Exception:
            log.exception("error inesperado")
        if once:
            break
        time.sleep(cfg["poll_seconds"])


if __name__ == "__main__":
    main()
