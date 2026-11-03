#!/usr/bin/env bash
# Runs the assembled Flink jar with `flink run` on the Flink and Java versions EMR runs it with, reading gzipped
# input and writing recovered events to files. Tests run on sbt's classpath; this runs the jar that is released.
#
# Usage: flink/src/smoke/run.sh <assembled jar>
set -euo pipefail

JAR=$(realpath "$1")
IMAGE=${FLINK_IMAGE:-flink:1.20.0-java11}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'docker rm -f event-recovery-smoke >/dev/null 2>&1 || true' EXIT

mkdir -p "$WORK/input" "$WORK/out"
chmod 777 "$WORK/out"
cp "$JAR" "$WORK/job.jar"
gzip -c "$HERE/bad-rows.txt" > "$WORK/input/bad-rows.gz"

b64() { base64 | tr -d '\n'; }
CONFIG=$(printf '%s' '{"schema":"iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/2-0-0","data":{"iglu:com.snowplowanalytics.snowplow.badrows/enrichment_failures/jsonschema/1-0-*":[{"name":"default","conditions":[],"steps":[]}]}}' | b64)
RESOLVER=$(printf '%s' '{"schema":"iglu:com.snowplowanalytics.iglu/resolver-config/jsonschema/1-0-1","data":{"cacheSize":500,"repositories":[{"name":"Iglu Central","priority":0,"vendorPrefixes":["com.snowplowanalytics"],"connection":{"http":{"uri":"http://iglucentral.com"}}}]}}' | b64)

docker run -d --name event-recovery-smoke -v "$WORK:/data" "$IMAGE" sleep infinity >/dev/null
docker exec event-recovery-smoke /opt/flink/bin/start-cluster.sh
docker exec event-recovery-smoke /opt/flink/bin/flink run /data/job.jar \
  --input file:///data/input \
  --directory-output file:///data/out/recovered \
  --failed file:///data/out/failed \
  --unrecoverable file:///data/out/unrecoverable \
  --region eu-central-1 \
  --resolver "$RESOLVER" \
  --config "$CONFIG" \
  --checkpoint-interval 1 \
  --checkpoint-dir file:///data/out/checkpoints

SUMMARY=$(docker exec event-recovery-smoke bash -c 'grep -h "Recovery finished" /opt/flink/log/*client*.log')
echo "$SUMMARY"
RECOVERED=$(find "$WORK/out/recovered" -type f ! -name '.*' -exec cat {} + | wc -l | tr -d ' ')
FAILED=$(find "$WORK/out/failed" -type f ! -name '.*' -exec cat {} + | wc -l | tr -d ' ')
echo "recovered lines: $RECOVERED, failed lines: $FAILED"

grep -q "Recovery finished: 3 recovered" <<<"$SUMMARY"
[ "$RECOVERED" = 3 ]
[ "$FAILED" = 1 ]
echo "Smoke test passed"
