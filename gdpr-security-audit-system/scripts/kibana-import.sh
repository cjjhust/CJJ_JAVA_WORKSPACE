#!/usr/bin/env bash
set -euo pipefail

KIBANA_URL="${KIBANA_URL:-http://localhost:5601}"
OBJECTS_FILE="${1:-kibana/audit-dashboard.ndjson}"
EXTRA_OBJECTS_FILE="${2:-kibana/audit-dashboard-extra.ndjson}"
FINAL_OBJECTS_FILE="${3:-kibana/audit-dashboard-final.ndjson}"
XY_FIX_FILE="${4:-kibana/audit-dashboard-xy-fix.ndjson}"

if [[ ! -f "$OBJECTS_FILE" ]]; then
  printf 'Saved Objects file not found: %s\n' "$OBJECTS_FILE" >&2
  exit 1
fi

if [[ ! -f "$EXTRA_OBJECTS_FILE" ]]; then
  printf 'Additional Saved Objects file not found: %s\n' "$EXTRA_OBJECTS_FILE" >&2
  exit 1
fi

if [[ ! -f "$FINAL_OBJECTS_FILE" ]]; then
  printf 'Final Saved Objects file not found: %s\n' "$FINAL_OBJECTS_FILE" >&2
  exit 1
fi

if [[ ! -f "$XY_FIX_FILE" ]]; then
  printf 'XY visualization fix file not found: %s\n' "$XY_FIX_FILE" >&2
  exit 1
fi

curl --fail --silent --show-error \
  -X POST "$KIBANA_URL/api/saved_objects/_import?overwrite=true" \
  -H 'kbn-xsrf: true' \
  -F "file=@${OBJECTS_FILE};type=application/ndjson" \
  | jq -e '(.success == true) and ((.errors // []) | length == 0)'

curl --fail --silent --show-error \
  -X POST "$KIBANA_URL/api/saved_objects/_import?overwrite=true" \
  -H 'kbn-xsrf: true' \
  -F "file=@${EXTRA_OBJECTS_FILE};type=application/ndjson" \
  | jq -e '(.success == true) and ((.errors // []) | length == 0)'

curl --fail --silent --show-error \
  -X POST "$KIBANA_URL/api/saved_objects/_import?overwrite=true" \
  -H 'kbn-xsrf: true' \
  -F "file=@${FINAL_OBJECTS_FILE};type=application/ndjson" \
  | jq -e '(.success == true) and ((.errors // []) | length == 0)'

curl --fail --silent --show-error \
  -X POST "$KIBANA_URL/api/saved_objects/_import?overwrite=true" \
  -H 'kbn-xsrf: true' \
  -F "file=@${XY_FIX_FILE};type=application/ndjson" \
  | jq -e '(.success == true) and ((.errors // []) | length == 0)'

curl --fail --silent --show-error \
  "$KIBANA_URL/api/saved_objects/dashboard/gdpr-audit-dashboard" \
  | jq -e '.id == "gdpr-audit-dashboard" and .attributes.title == "GDPR Audit Dashboard" and (.references | length == 9)'
