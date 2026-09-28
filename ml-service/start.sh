#!/bin/sh
# ML_WORKERS > 1 runs several uvicorn processes (each loads its own copy of the models); Prometheus
# then needs a shared directory to aggregate every worker's samples.
set -e
WORKERS="${ML_WORKERS:-1}"
if [ "$WORKERS" -gt 1 ]; then
  export PROMETHEUS_MULTIPROC_DIR="${PROMETHEUS_MULTIPROC_DIR:-/tmp/prometheus-multiproc}"
  rm -rf "$PROMETHEUS_MULTIPROC_DIR"
  mkdir -p "$PROMETHEUS_MULTIPROC_DIR"
fi
exec uvicorn main:app --host 0.0.0.0 --port 8000 --workers "$WORKERS" --no-access-log
