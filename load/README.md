# Load test: `POST /api/analyze`

`analyze.js` is a [k6](https://k6.io) script for the full image path: backend → ml-service `/infer` (forgery model, NSFW check, Grad-CAM, pHash, forensic signature, C2PA read) → Postgres → ml-service `/provenance/embed` (HMAC XMP + C2PA labelled copy). `synthetic-224.jpg` is a synthetic 224×224 colour-stripe pattern. No real imagery is used.

It uses an **open model** (`ramping-arrival-rate`). Requests arrive at the target rate whether or not earlier ones have finished. Past saturation you see rising latency and `dropped iterations`, instead of a closed loop quietly slowing down to match the server.

## Running it

Run k6 on the compose network so host networking isn't part of the measurement:

```bash
# 30 s at 1 req/s
docker run --rm -i --network forgedmediadetection_default -v "$PWD/load:/load" grafana/k6 \
  run -e BASE_URL=http://backend:8080 /load/analyze.js

# Step the arrival rate up to find the saturation point (req/s per step, 45 s per step)
docker run --rm -i --network forgedmediadetection_default -v "$PWD/load:/load" grafana/k6 \
  run -e BASE_URL=http://backend:8080 -e PROFILE=ramp -e STEPS=0.5,1,2,3,4,6,8,10,12 -e STEP_DURATION=45s /load/analyze.js
```

| Variable | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://localhost:8081` | Backend to test |
| `PROFILE` | `smoke` | `smoke` (1 req/s for 30 s) or `ramp` |
| `STEPS` / `STEP_DURATION` | `0.5,1,1.5,2,3,4` / `1m` | Arrival rates for `ramp`, each held for `STEP_DURATION` after a 10 s ramp |
| `SAMPLE` | `./synthetic-224.jpg` | Image to upload |
| `SUMMARY_JSON` | empty | Also write k6's full summary to this path |

The thresholds fail the run if more than 1% of requests fail or p95 exceeds 5 s.

> Every request stores an analysis row, and with `DECISION_FORGERY_REVIEW=0.05` it also lands in the moderation queue. To keep the primary database clean, point the test at a second backend on a scratch database:
> ```bash
> docker compose exec postgres createdb -U forged fmd_load
> docker compose run -d --no-deps --name fmd-load-backend \
>   -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/fmd_load \
>   -e STATE_STORE=memory -e MANAGEMENT_HEALTH_REDIS_ENABLED=false backend
> # ... run k6 with BASE_URL=http://fmd-load-backend:8080, then:
> docker rm -f fmd-load-backend && docker compose exec postgres dropdb -U forged fmd_load
> ```

Per-step throughput and latency come from the ml-service's Prometheus metrics (`fmd_ml_requests_total` and `fmd_ml_request_duration_seconds`). Use the Grafana dashboard's *ML service* panels or query Prometheus directly.

## Measured: CPU-only ml-service

- **Machine:** Intel Core i5-13420H laptop CPU (8 cores / 12 threads), 16 GB RAM, Docker Desktop on Windows 11 (WSL 2).
- **Stack:** the whole compose stack, both backends, Prometheus, Grafana and k6 all ran on the same machine and shared the CPU. The ml-service had `ML_WORKERS=1` (PyTorch uses 6 intra-op threads) and the heuristic NSFW backend. Measured 28 Sep 2026.

| Target (req/s) | Served (req/s) | ml `/infer` p50 | ml `/infer` p95 | What happened |
|---:|---:|---:|---:|---|
| 0.5 – 6 | = target | 0.16 – 0.18 s | 0.24 s | End-to-end p50 263 ms, p95 375 ms, p99 455 ms; no failures |
| 7 | 7.0 | 0.15 s | 0.24 s | Keeping up |
| 8 | 8.0 | 0.16 s | 0.24 s | At the knee: up to 3 requests in flight in the ml-service |
| 10 | 7.3 | 0.42 s | 0.94 s | **Saturated:** requests queue, ~8 in flight |
| 12 | 7.8 | 0.40 s | 0.90 s | Saturated; end-to-end p95 8.8 s, p99 9.7 s, and k6 dropped 346 iterations over the two top steps |

**Saturation point: about 8 req/s** (about 480 images per minute) on this machine. Below it the backend adds about 90 ms to the ml-service time: the DB write, the labelled-copy call and JSON encoding. Past it, extra load only adds queueing delay. Nothing failed, because the slowest request (17 s) stayed well under the backend's ml-service timeout (`ML_SERVICE_TIMEOUT`, 60 s). Sustained overload beyond that turns into `502`s rather than an unbounded queue.

**More ml-service workers barely help on a shared CPU.** With `ML_WORKERS=2` the plateau rose from about 7.5 to about 8.4 req/s at 10–12 req/s offered, with the same p95 of about 0.95 s. Each worker loads its own models and competes for the same 12 threads as the backend, Postgres and k6. Extra workers pay off on a dedicated machine with more cores. Otherwise, scale out: run more ml-service replicas on separate hosts behind `ML_SERVICE_URL`.

Use the table for capacity planning, not as a benchmark. The ml-service p95 of about 0.24 s is the useful baseline for the `AnalyzeLatencyHigh` alert (`ops/prometheus/alerts.yml`), which fires when the backend's p95 stays over 5 s for 10 minutes.

## Video and batching

A video is scored as up to `VIDEO_MAX_FRAMES` (64) frames, so one clip costs up to about 64 image inferences. The forgery model scores frames in batches of `VIDEO_BATCH_SIZE` (default 8) in one forward pass. Batching changes nothing in the output: a test checks that batched scores match single-image scores to 1e-5. Forgery-model time for 64 sampled frames on the same CPU (best of three):

| `VIDEO_BATCH_SIZE` | 1 | 4 | 8 | 16 | 32 |
|---|---:|---:|---:|---:|---:|
| frames/s | 61 | 50 | **70** | 66 | 61 |

On a CPU the gain is small (about 15% at 8), because PyTorch already spreads a single image across its threads. Batching pays off on a GPU, where one image leaves most of the device idle.

## GPU option

`docker-compose.gpu.yml` builds the ml-service against a CUDA PyTorch wheel (`TORCH_INDEX=cu124`), sets `ML_DEVICE=cuda` and reserves one NVIDIA GPU:

```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build
```

It needs an NVIDIA GPU and the NVIDIA Container Toolkit. Keep `ML_WORKERS=1` so one process owns the GPU, and raise `VIDEO_BATCH_SIZE` (16–32) so each forward pass fills it. **Not measured here:** the test machine has no NVIDIA GPU. Re-run the ramp above to get real numbers before sizing a GPU deployment. On a GPU the forward pass stops being the bottleneck, and the CPU-bound work around it (JPEG/PNG decode, Grad-CAM's backward pass, C2PA signing) sets the new ceiling.
