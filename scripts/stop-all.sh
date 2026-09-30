#!/usr/bin/env bash
# Stops everything started by start-all.sh.
cd "$(dirname "$0")/.."
for f in .run/*.pid; do
  [ -f "$f" ] || continue
  pid=$(cat "$f"); name=$(basename "$f" .pid)
  if kill "$pid" 2>/dev/null; then echo "stopped $name ($pid)"; fi
  rm -f "$f"
done
# npm spawns child node processes: clean up anything still holding the dev ports
for p in 5173 8090 8091 8092; do
  pid=$( (lsof -ti tcp:$p 2>/dev/null || true) | head -1 )
  [ -n "$pid" ] && kill "$pid" 2>/dev/null && echo "freed port $p"
done
exit 0
