#!/usr/bin/env bash
# Drop N sample files into the inbox: scripts/drop-files.sh 10
# Files are written as *.tmp and then renamed, so workers never see half-written files.
set -euo pipefail
cd "$(dirname "$0")/.."
count="${1:-10}"
inbox="${INBOX_DIR:-data/inbox}"
mkdir -p "$inbox"
stamp=$(date +%H%M%S)
for i in $(seq 1 "$count"); do
  name="orders-$stamp-$i.csv"
  seq 1 $((RANDOM % 50 + 1)) | sed 's/^/order,/' > "$inbox/$name.tmp"
  mv "$inbox/$name.tmp" "$inbox/$name"
done
echo "Dropped $count files into $inbox"
