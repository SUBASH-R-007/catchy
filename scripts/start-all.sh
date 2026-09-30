#!/usr/bin/env bash
# CATCHY: build and start telemetry-service, both demo services and the dashboard (Linux/macOS/Git Bash).
# Usage: scripts/start-all.sh          (stop with scripts/stop-all.sh)
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f .env ] || { cp .env.example .env; echo "Created .env from .env.example (dev placeholders)."; }
# Load .env, but never override variables already set in the caller's environment (e.g. CATCHY_DASHBOARD_PORT=5180 scripts/start-all.sh)
while IFS='=' read -r k v; do
  case "$k" in ''|\#*) continue ;; esac
  v="${v%$'\r'}"
  [ -z "${!k+x}" ] && export "$k=$v"
done < .env
TP="${CATCHY_TELEMETRY_PORT:-8090}"; CP="${CATCHY_CLAIMS_PORT:-8091}"; EP="${CATCHY_ELIGIBILITY_PORT:-8092}"; DP="${CATCHY_DASHBOARD_PORT:-5173}"

mkdir -p logs .run
echo "==> Building (tests skipped; run ./mvnw test separately)"
sh ./mvnw -q -B -DskipTests package

start_jar () { # name port jar
  echo "==> Starting $1 on :$2"
  SERVER_PORT="$2" nohup java -jar "$3" > "logs/$1.log" 2>&1 &
  echo $! > ".run/$1.pid"
}
wait_for () { # url name
  for _ in $(seq 1 60); do curl -fs "$1" > /dev/null 2>&1 && { echo "    $2 is up"; return 0; }; sleep 1; done
  echo "    $2 did not become healthy; see logs/"; return 1
}

start_jar telemetry "$TP" telemetry-service/target/telemetry-service-1.0.0-SNAPSHOT.jar
wait_for "http://localhost:$TP/api/v1/health" telemetry-service
start_jar claims "$CP" demo-claims-service/target/demo-claims-service-1.0.0-SNAPSHOT.jar
start_jar eligibility "$EP" demo-eligibility-service/target/demo-eligibility-service-1.0.0-SNAPSHOT.jar
wait_for "http://localhost:$CP/api/health" demo-claims-service
wait_for "http://localhost:$EP/api/health" demo-eligibility-service

echo "==> Starting dashboard on :$DP"
cd dashboard
[ -d node_modules ] || npm install --no-audit --no-fund
# --strictPort: fail loudly if the port is taken (e.g. another Vite app on 5173) instead of silently moving to another port
CATCHY_API_URL="http://localhost:$TP" nohup npm run dev -- --port "$DP" --strictPort > ../logs/dashboard.log 2>&1 &
echo $! > ../.run/dashboard.pid
cd ..
sleep 4
if ! curl -fs "http://localhost:$DP/" > /dev/null 2>&1; then
  echo "!! Dashboard did not start on :$DP (port in use?). See logs/dashboard.log. Try: CATCHY_DASHBOARD_PORT=5180 scripts/start-all.sh"
fi

cat <<EOF

CATCHY is starting.
  Dashboard         http://localhost:$DP        (pick a role on the login page)
  Telemetry API     http://localhost:$TP/api/v1/health
  Claims demo       http://localhost:$CP/api/health
  Eligibility demo  http://localhost:$EP/api/health
Generate traffic:  scripts/demo-load.sh       Logs: ./logs    Stop: scripts/stop-all.sh
EOF
