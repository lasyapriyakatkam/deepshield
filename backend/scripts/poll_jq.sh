#!/usr/bin/env bash
# Simple uploader + jq-based poller
set -euo pipefail
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required. Install with: brew install jq" >&2
  exit 2
fi
OUT=/tmp/deepshield_resp.json
HTTP=$(curl -s -o "$OUT" -w "%{http_code}" -X POST http://127.0.0.1:8080/api/scan/upload -F "file=@/tmp/deepshield_sample.png")
echo "Upload HTTP: $HTTP"
cat "$OUT" | jq .
ID=$(jq -r '.id' "$OUT")
if [ -z "$ID" ] || [ "$ID" = "null" ]; then
  echo "Failed to parse id from response" >&2
  exit 3
fi
for i in $(seq 1 30); do
  echo "Poll $i"
  curl -s http://127.0.0.1:8080/api/scan/$ID | jq .
  STATUS=$(curl -s http://127.0.0.1:8080/api/scan/$ID | jq -r '.status')
  echo "Status: $STATUS"
  if [ "$STATUS" = "COMPLETE" ] || [ "$STATUS" = "FAILED" ]; then
    break
  fi
  sleep 2
done
