#!/usr/bin/env bash
# hpa-demo.sh — Generate load on Spring PetClinic to trigger HPA scale-out
#
# Usage:
#   ./scripts/hpa-demo.sh [HOST] [CONCURRENCY] [DURATION_SECONDS]
#
# Defaults:
#   HOST        = petclinic.apps.apps-crc.testing
#   CONCURRENCY = 20   (parallel curl workers)
#   DURATION    = 120  (seconds to run load)

set -euo pipefail

PIDS=()
cleanup() {
  echo ""
  echo "Stopping workers..."
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  echo "Done."
  exit 0
}
trap cleanup INT TERM

HOST="${1:-petclinic.apps.apps-crc.testing}"
CONCURRENCY="${2:-20}"
DURATION="${3:-120}"
BASE_URL="http://${HOST}"

# Endpoints to cycle through — mix of HTML pages and API-style paths
PATHS=(
  "/"
  "/owners"
  "/owners/find"
  "/vets.html"
  "/owners/1"
  "/owners/2"
  "/owners/3"
  "/owners/1/pets/1/visits/new"
  "/actuator/health"
  "/actuator/info"
)

PATHS_LEN=${#PATHS[@]}

# ── helpers ──────────────────────────────────────────────────────────────────

check_deps() {
  for cmd in curl kubectl; do
    if ! command -v "$cmd" &>/dev/null; then
      echo "ERROR: '$cmd' is required but not found in PATH." >&2
      exit 1
    fi
  done
}

wait_for_app() {
  echo "Waiting for ${BASE_URL}/ to respond..."
  local attempts=0
  until curl -sf --max-time 5 "${BASE_URL}/" >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [[ $attempts -ge 30 ]]; then
      echo "ERROR: App not reachable at ${BASE_URL} after ${attempts} attempts. Aborting." >&2
      exit 1
    fi
    echo "  Attempt ${attempts}/30 — retrying in 5s..."
    sleep 5
  done
  echo "App is up!"
}

show_hpa() {
  local ns="${1:-production}"
  echo ""
  echo "──────────────────────────────────────────────────────"
  kubectl get hpa -n "$ns" 2>/dev/null || echo "(no HPA found in namespace '${ns}')"
  kubectl get pods -n "$ns" -l app=petclinic-prod 2>/dev/null \
    | awk 'NR==1{print} NR>1{print}' || true
  echo "──────────────────────────────────────────────────────"
  echo ""
}

# ── load worker ──────────────────────────────────────────────────────────────

run_worker() {
  local worker_id="$1"
  local end_time="$2"
  local hits=0 errors=0

  while [[ $(date +%s) -lt $end_time ]]; do
    local path="${PATHS[$((RANDOM % PATHS_LEN))]}"
    if curl -sf --max-time 5 "${BASE_URL}${path}" >/dev/null 2>&1; then
      hits=$((hits + 1))
    else
      errors=$((errors + 1))
    fi
  done

  echo "Worker ${worker_id}: ${hits} hits, ${errors} errors"
}

export -f run_worker
export BASE_URL PATHS PATHS_LEN

# ── main ─────────────────────────────────────────────────────────────────────

check_deps
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Spring PetClinic HPA Demo"
echo "  URL         : ${BASE_URL}"
echo "  Concurrency : ${CONCURRENCY} workers"
echo "  Duration    : ${DURATION}s"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

wait_for_app

echo ""
echo "=== HPA status BEFORE load ==="
show_hpa production

END_TIME=$(( $(date +%s) + DURATION ))

echo "Starting ${CONCURRENCY} load workers for ${DURATION}s..."
echo "(Press Ctrl-C to stop early)"
echo ""

# Launch workers in background
for i in $(seq 1 "$CONCURRENCY"); do
  bash -c "run_worker $i $END_TIME" &
  PIDS+=($!)
done

# Poll HPA every 15 seconds while load is running
POLL_INTERVAL=15
while [[ $(date +%s) -lt $END_TIME ]]; do
  remaining=$(( END_TIME - $(date +%s) ))
  echo "=== HPA status — ${remaining}s remaining ==="
  show_hpa production
  sleep "$POLL_INTERVAL"
done

# Wait for all workers to finish
for pid in "${PIDS[@]}"; do
  wait "$pid" 2>/dev/null || true
done

echo ""
echo "=== Load complete. HPA status AFTER load ==="
show_hpa production

echo ""
echo "Tip: watch scale-down with:"
echo "  kubectl get hpa,pods -n production -w"