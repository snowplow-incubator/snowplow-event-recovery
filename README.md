Snowplow Event Recovery
===========================

This repository contains the codebases used for recovering bad rows emitted by a Snowplow pipeline.

The different Snowplow pipelines being all non-lossy, if something goes wrong during, for example, schema validation or enrichment the payloads (alongside the errors that happened) are stored into a bad rows storage solution, be it a data stream or object storage, instead of being discarded.

The goal of recovery is to fix the payloads contained in these bad rows so that they are ready to be processed successfully by a Snowplow enrichment platform.

For detailed documentation see [docs.snowplow.io][techdocs]

# Configuration
Configuration mechanism allows for flexibility taking into account the most common usecases. Configuration is constructed with self-describing JSON consisting with 3 main concepts:

## Steps
Steps are individual modifications applied to Bad Row payloads as atomic parts of recovery flows (scenarios).

## Conditions
Conditions are boolean expressions that operate on BadRow fields. If a conditions are satisfied, corresponding steps are applied. Otherwise next set of conditons is checked. If no conditions match row is marked failed with missing configuration.

## Flows
Flows are sequences of Steps applied one by one.

# Running
1. Define config
2. Encode config
3. Choose runner and deploy:
- Beam
- Spark (deprecated)
- Flink

## Flink job: arguments
The Flink job accepts the argument names of the Spark and Beam jobs as well as its own: `--failedOutput` for `--failed`, `--unrecoverableOutput` for `--unrecoverable` and `--batchSize` for `--batch-size`. `--batch-size` must be between 1 and 500, the most records Kinesis accepts in one PutRecords call.

Without `--failed`, failed bad rows go to `<input>/unrecovered`; without `--unrecoverable`, unrecoverable ones go to `<failed>/unrecoverable`, as with the Spark and Beam jobs. These defaults sit under the input path and the job reads its input path including subdirectories, so a rerun on the same input would read them back. Pass both paths when the input will be read again.

## Flink job: delivery and counts
The Flink job retries records that Kinesis throttles until they are written. A record rejected with a non-retryable error fails the job instead of being dropped. So when the job finishes successfully, every recovered event is in the output stream.

On success the job logs a summary:

```
Recovery finished: <n> recovered and written to Kinesis stream <stream>, <n> failed (written to <path>), <n> unrecoverable (written to <path>)
```

`failed` and `unrecoverable` are bad rows the recovery configuration could not fix. They are written to S3 and never reach Kinesis. Each input event is counted once, even when the job restarted from a checkpoint (see below).

The live metrics, reported through the configured metrics reporter (statsd in `.dataflow-runner/flink-cluster.json.tmpl`), mean:

| Metric | Meaning |
|---|---|
| `events.recovered`, `events.failed`, `events.unrecoverable` | Events counted as they leave the recovery step, before any write. `recovered` is what was handed to the Kinesis sink, not what Kinesis accepted. |
| `numRecordsOut` on the `kinesis: Writer` operator | Records sent to Kinesis, counting each retry again. |
| `numRecordsOutErrors` on the `kinesis: Writer` operator | Records Kinesis rejected and that will be retried. |

While the job runs, records written so far is `numRecordsOut` minus `numRecordsOutErrors` on `kinesis: Writer`. This relies on Kinesis reporting an accurate `FailedRecordCount` for PutRecords. It has not been verified against real Kinesis: the integration tests use LocalStack, which reports a `FailedRecordCount` of 1 for any throttled batch.

## Flink job: checkpointing and resuming
Pass `--checkpoint-interval <seconds>` and `--checkpoint-dir <path>` to turn on checkpointing. The input position, the events waiting in the Kinesis sink and the counts are saved in each checkpoint.

- **A task fails during the run** (for example a lost node): Flink restarts the job from its last checkpoint, up to 3 times with 10 seconds between attempts. A `restart-strategy` set in the cluster's Flink configuration is used instead of this default (the job checks for one; this is not covered by a test). The summary then says how many times the job restarted.
- **The run fails for good** (restarts used up, or the step was stopped): its checkpoints are kept under `<checkpoint-dir>/<job id>/chk-<n>`. Rerun on the same input with `flink run -s <checkpoint-dir>/<job id>/chk-<highest n>` to continue from there. The failure is logged with these directions. Resuming is tested on Flink's local test cluster, not yet on EMR.

Delivery to Kinesis is at-least-once. Events the sink sent after the checkpoint a run continues from are sent again, so the stream can hold some events twice. Each event is still counted once in the summary. Without checkpointing, a failed run cannot be continued: rerunning on the same input sends again every event the failed run had written.

# Extending recovery
There are several extension points for recovery: Steps, Conditions or additional [BadRow][badrows] types.

## Find out more

| Technical Docs              | Setup Guide           |
|-----------------------------|-----------------------|
| ![i1][techdocs-image]       | ![i2][setup-image]    |
| [Technical Docs][techdocs]  | [Setup Guide][setup]  |

## Copyright and license

Copyright 2023 Snowplow Analytics Ltd.

Licensed under the [Apache License, Version 2.0][license] (the "License");
you may not use this software except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

[license]: http://www.apache.org/licenses/LICENSE-2.0

[badrows]: https://github.com/snowplow-incubator/snowplow-badrows
[techdocs-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/techdocs.png
[setup-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/setup.png
[techdocs]: https://docs.snowplow.io/docs/managing-data-quality/snowplow-event-recovery/
