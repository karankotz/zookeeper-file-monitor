#!/usr/bin/env bash
# Start a 3-server ZooKeeper ensemble (localhost:2181-2183) and DynamoDB Local
# (http://localhost:8000) in one JVM. No Docker needed. Ctrl+C to stop.
set -euo pipefail
cd "$(dirname "$0")/.."
exec mvn -q test-compile exec:java@local-infra
