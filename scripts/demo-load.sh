#!/usr/bin/env bash
# Generates visible traffic: claims + eligibility workloads, then a policy-comparison simulation via the telemetry API.
# Usage: scripts/demo-load.sh [rounds]     (default 2)
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  while IFS='=' read -r k v; do
    case "$k" in ''|\#*) continue ;; esac
    v="${v%$'\r'}"
    [ -z "${!k+x}" ] && export "$k=$v"
  done < .env
fi
TP="${CATCHY_TELEMETRY_PORT:-8090}"; CP="${CATCHY_CLAIMS_PORT:-8091}"; EP="${CATCHY_ELIGIBILITY_PORT:-8092}"
ROUNDS="${1:-2}"
post () { echo "--- POST $1"; curl -s -X POST -H 'Content-Type: application/json' -d "${2:-{\}}" "$1"; echo; }

for i in $(seq 1 "$ROUNDS"); do
  echo "===== round $i ====="
  post "http://localhost:$CP/api/demo/claims/workload/repeated" '{"requests":400}'
  post "http://localhost:$CP/api/demo/claims/workload/changing" '{"requests":400}'
  post "http://localhost:$CP/api/demo/claims/workload/expire"   '{"requests":60}'
  post "http://localhost:$EP/api/demo/eligibility/workload/repeated"   '{"requests":400}'
  post "http://localhost:$EP/api/demo/eligibility/workload/high-churn" '{"requests":800}'
done

echo "===== telemetry-service simulation (engineer role) ====="
TOKEN=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"role":"ENGINEER"}' "http://localhost:$TP/api/v1/auth/demo-login" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
if [ -n "$TOKEN" ]; then
  for kind in sample-workload ttl-expiration high-load policy-comparison; do
    echo "--- simulate/$kind"
    curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' "http://localhost:$TP/api/v1/applications/1/simulate/$kind" | head -c 400; echo
  done
else
  echo "demo-login unavailable (CATCHY_DEMO_MODE=false?); log in on the dashboard and use the Simulations page."
fi
echo "Done. Open the dashboard: http://localhost:${CATCHY_DASHBOARD_PORT:-5173}"
