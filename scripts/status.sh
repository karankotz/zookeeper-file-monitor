#!/usr/bin/env bash
# Show live workers and current claims (ZooKeeper) and the registry (DynamoDB).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f target/file-monitor.jar ] || mvn -q package -DskipTests
export DYNAMODB_ENDPOINT="${DYNAMODB_ENDPOINT-http://localhost:8000}"
exec java -jar target/file-monitor.jar status 2>/dev/null
