#!/usr/bin/env bash
# Starts the Beam Docker image, on the JVM it ships with, far enough to validate a recovery configuration. That
# fetches the configuration's schema from Iglu Central and validates the configuration against it. Without an input
# and an output the job then stops before starting a pipeline.
#
# Usage: beam/src/smoke/run.sh <docker image>
set -euo pipefail

IMAGE=$1

b64() { base64 | tr -d '\n'; }
RESOLVER=$(printf '%s' '{"schema":"iglu:com.snowplowanalytics.iglu/resolver-config/jsonschema/1-0-1","data":{"cacheSize":500,"repositories":[{"name":"Iglu Central","priority":0,"vendorPrefixes":["com.snowplowanalytics"],"connection":{"http":{"uri":"http://iglucentral.com"}}}]}}' | b64)
VALID=$(printf '%s' '{"schema":"iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/4-0-0","data":{"iglu:com.snowplowanalytics.snowplow.badrows/enrichment_failures/jsonschema/1-0-*":[{"name":"default","conditions":[],"steps":[]}]}}' | b64)
INVALID=$(printf '%s' '{"schema":"iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/4-0-0","data":{"iglu:com.snowplowanalytics.snowplow.badrows/enrichment_failures/jsonschema/1-0-*":[{"name":"default","conditions":[],"steps":[{"op":"Nope"}]}]}}' | b64)

run() { # $1 = base64 configuration. Prints what the job reports, apart from the missing input and output.
  docker run --rm "$IMAGE" --resolver="$RESOLVER" --config="$1" 2>&1 |
    grep -v -e "is mandatory" -e "^SLF4J" -e "^WARNING" || true
}

echo "== logging"
if docker run --rm "$IMAGE" 2>&1 | grep "No SLF4J providers"; then
  exit 1
fi

echo "== $(docker run --rm --entrypoint java "$IMAGE" -version 2>&1 | head -1)"

echo "== valid configuration"
OUT=$(run "$VALID")
if [ -n "$OUT" ]; then
  echo "$OUT"
  exit 1
fi

echo "== invalid configuration"
OUT=$(run "$INVALID")
echo "$OUT" | head -5
grep -q "Instance is not valid against its schema" <<<"$OUT"

echo "Smoke test passed"
