#!/usr/bin/env bash
# Runs the assembled Flink jar on the Flink and Java versions EMR runs it with, reading gzipped input and writing
# recovered events to files. Tests run on sbt's classpath; this runs the jar that is released, twice:
#
# - session: `flink run` against a session cluster. The jar is only loaded by the user-code classloader.
# - system classpath: the jar in Flink's lib/, run in application mode. On YARN (`flink run -m yarn-cluster`), the
#   jar is on the JobManager's system classpath ahead of Flink's own lib, so what it bundles can break the cluster
#   (a logging binding, a second copy of Flink's classes). This run puts it there too.
#
# Usage: flink/src/smoke/run.sh <assembled jar>
set -euo pipefail

JAR=$(realpath "$1")
IMAGE=${FLINK_IMAGE:-flink:1.20.0-java11}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
CONTAINER=event-recovery-smoke
trap 'docker rm -f $CONTAINER >/dev/null 2>&1 || true' EXIT

mkdir -p "$WORK/input"
chmod 777 "$WORK"
cp "$JAR" "$WORK/job.jar"
gzip -c "$HERE/bad-rows.txt" > "$WORK/input/bad-rows.gz"

b64() { base64 | tr -d '\n'; }
CONFIG=$(printf '%s' '{"schema":"iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/2-0-0","data":{"iglu:com.snowplowanalytics.snowplow.badrows/enrichment_failures/jsonschema/1-0-*":[{"name":"default","conditions":[],"steps":[]}]}}' | b64)
RESOLVER=$(printf '%s' '{"schema":"iglu:com.snowplowanalytics.iglu/resolver-config/jsonschema/1-0-1","data":{"cacheSize":500,"repositories":[{"name":"Iglu Central","priority":0,"vendorPrefixes":["com.snowplowanalytics"],"connection":{"http":{"uri":"http://iglucentral.com"}}}]}}' | b64)

job_args() { # $1 = output directory under /data
  echo --input file:///data/input \
    --directory-output "file:///data/$1/recovered" \
    --failed "file:///data/$1/failed" \
    --unrecoverable "file:///data/$1/unrecoverable" \
    --region eu-central-1 \
    --resolver "$RESOLVER" \
    --config "$CONFIG" \
    --checkpoint-interval 1 \
    --checkpoint-dir "file:///data/$1/checkpoints"
}

check() { # $1 = output directory, $2 = log text holding the summary
  local summary recovered failed
  summary=$(grep -h "Recovery finished" <<<"$2" || true)
  recovered=$(find "$WORK/$1/recovered" -type f ! -name '.*' -exec cat {} + 2>/dev/null | wc -l | tr -d ' ')
  failed=$(find "$WORK/$1/failed" -type f ! -name '.*' -exec cat {} + 2>/dev/null | wc -l | tr -d ' ')
  echo "$1: ${summary:-no summary logged}"
  echo "$1: recovered lines: $recovered, failed lines: $failed"
  grep -q "Recovery finished: 3 recovered" <<<"$summary" && [ "$recovered" = 3 ] && [ "$failed" = 1 ]
}

echo "== session cluster, flink run"
docker run -d --name $CONTAINER -v "$WORK:/data" "$IMAGE" sleep infinity >/dev/null
docker exec $CONTAINER /opt/flink/bin/start-cluster.sh
# shellcheck disable=SC2046
docker exec $CONTAINER /opt/flink/bin/flink run /data/job.jar $(job_args session)
check session "$(docker exec $CONTAINER bash -c 'cat /opt/flink/log/*client*.log')"
docker rm -f $CONTAINER >/dev/null

echo "== job jar on the system classpath, application mode"
docker run -d --name $CONTAINER -v "$WORK:/data" "$IMAGE" sleep infinity >/dev/null
docker exec $CONTAINER cp /data/job.jar /opt/flink/lib/event-recovery.jar
docker exec $CONTAINER /opt/flink/bin/taskmanager.sh start
# shellcheck disable=SC2046
# Attached, like `flink run -m yarn-cluster`: execute() waits for the job and returns its accumulators (the summary)
LOG=$(docker exec $CONTAINER timeout 300 /opt/flink/bin/standalone-job.sh start-foreground -Dexecution.attached=true \
  --job-classname com.snowplowanalytics.snowplow.event.recovery.Main $(job_args system-classpath) 2>&1 || true)
if ! check system-classpath "$LOG"; then
  echo "$LOG" | grep -v "^\s*at " | tail -40
  exit 1
fi

echo "Smoke test passed"
