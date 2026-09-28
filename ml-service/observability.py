"""Phase 24: request IDs, structured JSON logs and Prometheus metrics for the ML service.

* Every request carries an ``X-Request-ID``: the backend's ID when it sends one, otherwise a new
  UUID. It is echoed on the response and attached to every log line written while serving it.
* ``LOG_FORMAT=json`` switches all logging (including uvicorn's) to one JSON object per line with
  the same field names the backend uses (``@timestamp``, ``log.level``, ``message``, ``request_id``).
* ``/metrics`` serves Prometheus metrics. With several uvicorn workers set
  ``PROMETHEUS_MULTIPROC_DIR`` (``start.sh`` does) so every worker's samples are aggregated.
"""

from __future__ import annotations

import contextvars
import json
import logging
import os
import re
import time
import uuid
from datetime import datetime, timezone

from fastapi import FastAPI, Request, Response
from prometheus_client import (
    CONTENT_TYPE_LATEST,
    REGISTRY,
    CollectorRegistry,
    Counter,
    Gauge,
    Histogram,
    generate_latest,
)

REQUEST_ID_HEADER = "X-Request-ID"
SERVICE_NAME = "ml-service"
_VALID_REQUEST_ID = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")

request_id_var: contextvars.ContextVar[str | None] = contextvars.ContextVar("request_id", default=None)

REQUESTS = Counter(
    "fmd_ml_requests_total", "Requests served, by route template and status.", ["endpoint", "method", "status"]
)
LATENCY = Histogram(
    "fmd_ml_request_duration_seconds",
    "Request latency by route template.",
    ["endpoint", "method"],
    buckets=(0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0),
)
ERRORS = Counter("fmd_ml_errors_total", "Requests that ended in a 5xx response.", ["endpoint"])
IN_FLIGHT = Gauge("fmd_ml_requests_in_flight", "Requests currently being served.", multiprocess_mode="livesum")
SCORES = Histogram(
    "fmd_ml_score",
    "Distribution of served model scores (drift signal).",
    ["model"],
    buckets=(0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.85, 0.9, 0.95, 1.0),
)
VIDEO_FRAMES = Histogram(
    "fmd_ml_video_frames", "Frames sampled per video.", buckets=(1, 2, 4, 8, 16, 32, 64, 128)
)
MODELS_LOADED = Gauge("fmd_ml_models_loaded", "1 once the forgery model is loaded.", multiprocess_mode="max")


def new_request_id(incoming: str | None) -> str:
    """The caller's ID when it is well formed, otherwise a fresh UUID (never trust arbitrary header text)."""
    if incoming and _VALID_REQUEST_ID.match(incoming):
        return incoming
    return uuid.uuid4().hex


class JsonFormatter(logging.Formatter):
    """One JSON object per line, ECS-style keys so backend and ml-service logs can be queried together."""

    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "@timestamp": datetime.fromtimestamp(record.created, tz=timezone.utc).isoformat(timespec="milliseconds"),
            "log.level": record.levelname,
            "log.logger": record.name,
            "service.name": SERVICE_NAME,
            "message": record.getMessage(),
        }
        request_id = request_id_var.get()
        if request_id:
            payload["request_id"] = request_id
        for key in ("http_method", "http_path", "http_status", "duration_ms"):
            value = getattr(record, key, None)
            if value is not None:
                payload[key] = value
        if record.exc_info:
            payload["error.stack_trace"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False)


class _RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get() or "-"
        return True


def configure_logging() -> None:
    """Idempotent. ``LOG_FORMAT=json`` for JSON lines, anything else for readable text."""
    json_logs = os.getenv("LOG_FORMAT", "text").lower() == "json"
    handler = logging.StreamHandler()
    handler.addFilter(_RequestIdFilter())
    handler.setFormatter(
        JsonFormatter()
        if json_logs
        else logging.Formatter("%(asctime)s %(levelname)s [%(request_id)s] %(name)s: %(message)s")
    )
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(os.getenv("LOG_LEVEL", "INFO").upper())
    # uvicorn installs its own handlers before importing the app; route them through ours instead
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        lg = logging.getLogger(name)
        lg.handlers = []
        lg.propagate = True
    # Our middleware writes one access line per request with the request ID; uvicorn's would duplicate it
    logging.getLogger("uvicorn.access").disabled = True


access_log = logging.getLogger("access")


def _route_template(request: Request) -> str:
    route = request.scope.get("route")
    return getattr(route, "path", None) or "unmatched"


def install(app: FastAPI) -> None:
    """Adds the request-ID / metrics middleware and the ``/metrics`` route."""

    @app.middleware("http")
    async def observe(request: Request, call_next):
        rid = new_request_id(request.headers.get(REQUEST_ID_HEADER))
        token = request_id_var.set(rid)
        started = time.perf_counter()
        IN_FLIGHT.inc()
        status = 500
        try:
            response = await call_next(request)
            status = response.status_code
            response.headers[REQUEST_ID_HEADER] = rid
            return response
        finally:
            IN_FLIGHT.dec()
            elapsed = time.perf_counter() - started
            endpoint = _route_template(request)
            if endpoint != "/metrics":
                REQUESTS.labels(endpoint, request.method, str(status)).inc()
                LATENCY.labels(endpoint, request.method).observe(elapsed)
                if status >= 500:
                    ERRORS.labels(endpoint).inc()
                if endpoint != "/health":
                    access_log.info(
                        "%s %s %s %.1fms",
                        request.method,
                        endpoint,
                        status,
                        elapsed * 1000,
                        extra={
                            "http_method": request.method,
                            "http_path": endpoint,
                            "http_status": status,
                            "duration_ms": round(elapsed * 1000, 1),
                        },
                    )
            request_id_var.reset(token)

    @app.get("/metrics", include_in_schema=False)
    def metrics() -> Response:
        return Response(generate_latest(_registry()), media_type=CONTENT_TYPE_LATEST)


def _registry():
    if os.getenv("PROMETHEUS_MULTIPROC_DIR"):
        from prometheus_client import multiprocess

        registry = CollectorRegistry()
        multiprocess.MultiProcessCollector(registry)
        return registry
    return REGISTRY


def observe_scores(forgery: float | None, explicit: float | None) -> None:
    if forgery is not None:
        SCORES.labels("forgery").observe(forgery)
    if explicit is not None:
        SCORES.labels("explicit").observe(explicit)
