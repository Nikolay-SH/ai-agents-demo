#!/bin/sh
# One request each second; failures are retained in real application metrics/logs.
while true; do
  curl --silent --show-error --max-time 7 --output /dev/null \
    --write-out 'checkout status=%{http_code} seconds=%{time_total}\n' \
    --request POST http://checkout:8080/checkout || true
  sleep 1
done
