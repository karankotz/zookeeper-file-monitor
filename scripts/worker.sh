#!/usr/bin/env bash
# Run one worker node against the local infrastructure.
#   scripts/worker.sh node-1
# Override any setting with env vars, e.g. PROCESSING_DELAY_MS=5000 scripts/worker.sh node-2
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f target/file-monitor.jar ] || mvn -q package -DskipTests
export NODE_ID="${1:-node-$$}"
export DYNAMODB_ENDPOINT="${DYNAMODB_ENDPOINT-http://localhost:8000}"
export PROCESSING_DELAY_MS="${PROCESSING_DELAY_MS:-3000}"
exec java -jar target/file-monitor.jar worker
