#!/usr/bin/env bash
# Runs the whole recovery pipeline in the Beam Docker image, on the JVM and classpath it ships with, using Beam's
# DirectRunner. It reads the Flink smoke test's bad rows from a local file and writes recovered events to the Pub/Sub
# emulator, then checks what arrived: 3 recovered events on the topic and 1 failed bad row in files.
# It does not start Dataflow workers, which run Beam's own SDK container.
#
# Usage: beam/src/smoke/pipeline.sh <docker image>
set -euo pipefail

IMAGE=$1
EMULATOR_IMAGE=${EMULATOR_IMAGE:-gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
NETWORK=event-recovery-pipeline-smoke
EMULATOR=event-recovery-pubsub
PROJECT=smoke-test
TOPIC=projects/$PROJECT/topics/recovered
SUBSCRIPTION=projects/$PROJECT/subscriptions/recovered
API=http://localhost:8085/v1
trap 'docker rm -f $EMULATOR >/dev/null 2>&1 || true; docker network rm $NETWORK >/dev/null 2>&1 || true' EXIT

mkdir -p "$WORK/input"
chmod -R 777 "$WORK"
cp "$HERE/../../../flink/src/smoke/bad-rows.txt" "$WORK/input/bad-rows.txt"

b64() { base64 | tr -d '\n'; }
CONFIG=$(printf '%s' '{"schema":"iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/4-0-0","data":{"iglu:com.snowplowanalytics.snowplow.badrows/enrichment_failures/jsonschema/1-0-*":[{"name":"default","conditions":[],"steps":[]}]}}' | b64)
RESOLVER=$(printf '%s' '{"schema":"iglu:com.snowplowanalytics.iglu/resolver-config/jsonschema/1-0-1","data":{"cacheSize":500,"repositories":[{"name":"Iglu Central","priority":0,"vendorPrefixes":["com.snowplowanalytics"],"connection":{"http":{"uri":"http://iglucentral.com"}}}]}}' | b64)

echo "== Pub/Sub emulator"
docker network create $NETWORK >/dev/null
docker run -d --name $EMULATOR --network $NETWORK -p 8085:8085 "$EMULATOR_IMAGE" \
  gcloud beta emulators pubsub start --project=$PROJECT --host-port=0.0.0.0:8085 >/dev/null
for _ in $(seq 60); do curl -sf "$API/projects/$PROJECT/topics" >/dev/null && break; sleep 1; done
curl -sf -X PUT "$API/$TOPIC" >/dev/null
# Created before the job runs: the emulator drops messages published to a topic with no subscription
curl -sf -X PUT -H 'Content-Type: application/json' -d "{\"topic\":\"$TOPIC\"}" "$API/$SUBSCRIPTION" >/dev/null

echo "== recovery job, DirectRunner"
docker run --rm --network $NETWORK -v "$WORK:/data" "$IMAGE" \
  --runner=DirectRunner \
  --pubsubRootUrl=http://$EMULATOR:8085 \
  --credentialFactoryClass=org.apache.beam.sdk.extensions.gcp.auth.NoopCredentialFactory \
  --project=$PROJECT \
  --tempLocation=/data/tmp \
  --inputDirectory=/data/input/bad-rows.txt \
  --outputTopic=$TOPIC \
  --failedOutput=/data/failed \
  --unrecoverableOutput=/data/unrecoverable \
  --resolver="$RESOLVER" \
  --config="$CONFIG" 2>&1 | grep -v -e "^SLF4J" -e "^WARNING" -e "INFO com.spotify.scio.util.AvroClassTrust" || true

PULLED=$(curl -sf -X POST -H 'Content-Type: application/json' -d '{"maxMessages":100}' "$API/$SUBSCRIPTION:pull")
recovered=$(jq '.receivedMessages | length' <<<"$PULLED")
attributes=$(jq -r '[.receivedMessages[].message.attributes.recovered] | unique | join(",")' <<<"$PULLED")
failed=$(find "$WORK/failed" -type f ! -name '.*' -exec cat {} + 2>/dev/null | wc -l | tr -d ' ')
unrecoverable=$(find "$WORK/unrecoverable" -type f ! -name '.*' -exec cat {} + 2>/dev/null | wc -l | tr -d ' ')
echo "recovered messages: $recovered (recovered attribute: $attributes), failed lines: $failed, unrecoverable lines: $unrecoverable"
[ "$recovered" = 3 ] && [ "$attributes" = "$TOPIC" ] && [ "$failed" = 1 ] && [ "$unrecoverable" = 0 ]

echo "Pipeline smoke test passed"
