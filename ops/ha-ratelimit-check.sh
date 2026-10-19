#!/usr/bin/env sh
# Two backend instances share one rate-limit bucket through Redis.
#   docker compose -f docker-compose.yml -f docker-compose.ha.yml up -d --wait
#   API_KEY=<partner key> sh ops/ha-ratelimit-check.sh
# Issue a FREE-plan key from /api-access (or POST /api/admin/clients), then spend its budget
# (API_RATE_LIMIT_FREE, default 10/min) alternately on both instances and expect the next
# request to be refused on either. An empty POST costs a token without running a model.
set -eu

KEY="${API_KEY:?Set API_KEY to a FREE-plan partner key}"
A="${BACKEND_A:-http://localhost:8081}"
B="${BACKEND_B:-http://localhost:8082}"
LIMIT="${LIMIT:-10}"

post() {
  curl -s -o /dev/null -w "%{http_code}" -X POST -H "X-API-Key: $KEY" "$1/api/v1/analyze"
}

# Start from a full bucket (it refills at LIMIT per minute)
remaining=$(curl -s -D - -o /dev/null -H "X-API-Key: $KEY" "$A/api/v1/usage" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-ratelimit-remaining"{print $2}')
if [ "${remaining:-0}" -lt "$LIMIT" ]; then
  echo "bucket has $remaining/$LIMIT tokens; waiting 60s for it to refill"
  sleep 60
fi

i=0
while [ "$i" -lt "$LIMIT" ]; do
  if [ $((i % 2)) -eq 0 ]; then url="$A"; else url="$B"; fi
  code=$(post "$url")
  if [ "$code" = "429" ]; then
    echo "FAIL: request $((i + 1)) of $LIMIT was refused by $url"
    exit 1
  fi
  echo "request $((i + 1)) -> $url: $code"
  i=$((i + 1))
done

for url in "$A" "$B"; do
  code=$(post "$url")
  echo "over budget -> $url: $code"
  if [ "$code" != "429" ]; then
    echo "FAIL: $url accepted request $((LIMIT + 1)); the instances are not sharing the bucket"
    exit 1
  fi
done
echo "OK: $LIMIT requests spread over two instances exhausted one shared bucket"
