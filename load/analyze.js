// k6 load test for POST /api/analyze (backend -> ml-service inference -> Postgres -> labelled copy).
// Run on the compose network so nothing else is measured:
//   docker run --rm -i --network forgedmediadetection_default -v "$PWD/load:/load" grafana/k6 \
//     run -e BASE_URL=http://backend:8080 -e PROFILE=ramp /load/analyze.js
// PROFILE=smoke (30 s at 1 req/s) or ramp (steps up the arrival rate to find the saturation point).
import http from 'k6/http'
import { check } from 'k6'
import { Counter, Trend } from 'k6/metrics'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081'
const PROFILE = __ENV.PROFILE || 'smoke'
// Synthetic 224x224 colour pattern (no real imagery); see load/README.md
const SAMPLE = open(__ENV.SAMPLE || './synthetic-224.jpg', 'b')

const decisions = new Counter('fmd_decisions')
const serverTime = new Trend('fmd_server_time', true)

const STEPS = (__ENV.STEPS || '0.5,1,1.5,2,3,4').split(',').map(Number)
const STEP_DURATION = __ENV.STEP_DURATION || '1m'

const scenarios = {
  smoke: {
    executor: 'constant-arrival-rate',
    rate: 1,
    timeUnit: '1s',
    duration: '30s',
    preAllocatedVUs: 4,
    maxVUs: 20,
  },
  ramp: {
    // Open model: requests arrive at the target rate whether or not earlier ones finished, so queueing
    // shows up as rising latency and dropped iterations instead of being hidden by closed-loop VUs.
    executor: 'ramping-arrival-rate',
    startRate: STEPS[0] * 10,
    timeUnit: '10s',
    preAllocatedVUs: 10,
    maxVUs: 60,
    stages: STEPS.flatMap((rps) => [
      { target: rps * 10, duration: '10s' },
      { target: rps * 10, duration: STEP_DURATION },
    ]),
  },
}

export const options = {
  scenarios: { analyze: scenarios[PROFILE] },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{expected_response:true}': ['p(95)<5000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

export default function () {
  const res = http.post(
    `${BASE_URL}/api/analyze`,
    { file: http.file(SAMPLE, 'synthetic-224.jpg', 'image/jpeg') },
    { tags: { name: 'POST /api/analyze' }, timeout: '60s' },
  )
  const ok = check(res, {
    'status 200': (r) => r.status === 200,
    'has decision': (r) => r.status === 200 && Boolean(r.json('decision')),
  })
  if (ok) {
    decisions.add(1, { decision: res.json('decision') })
    serverTime.add(res.timings.waiting)
  }
}

export function handleSummary(data) {
  const out = { stdout: textSummary(data) }
  if (__ENV.SUMMARY_JSON) out[__ENV.SUMMARY_JSON] = JSON.stringify(data, null, 2)
  return out
}

function textSummary(data) {
  const d = data.metrics.http_req_duration?.values || {}
  const reqs = data.metrics.http_reqs?.values || {}
  const failed = data.metrics.http_req_failed?.values?.rate ?? 0
  const dropped = data.metrics.dropped_iterations?.values?.count ?? 0
  const ms = (v) => (v === undefined ? '-' : `${Math.round(v)} ms`)
  return [
    '',
    `profile ${PROFILE}  requests ${reqs.count ?? 0}  throughput ${(reqs.rate ?? 0).toFixed(2)} req/s`,
    `latency  med ${ms(d.med)}  p90 ${ms(d['p(90)'])}  p95 ${ms(d['p(95)'])}  p99 ${ms(d['p(99)'])}  max ${ms(d.max)}`,
    `failed ${(failed * 100).toFixed(2)}%  dropped iterations ${dropped}`,
    '',
  ].join('\n')
}
