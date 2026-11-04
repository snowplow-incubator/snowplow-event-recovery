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
- Spark: unsupported and no longer built or released. It drops events when Kinesis throttles writes and counts events before writing them. Use Flink for recoveries into Kinesis.
- Flink

## Flink job: arguments
Recovered events go to a Kinesis stream (`--output`), to files (`--directory-output`, also accepted as `--directoryOutput`), or both; at least one is required. Files are useful for a dry run before sending events to Kinesis. Each recovered event is one line of JSON keyed by the collector payload's field names (`path`, `querystring`, `body`, `headers`, ...), in files named `part-<id>-<n>.json` under one directory per hour (`<directory>/<yyyy-MM-dd--HH>/`). A run that recovers no events writes no files there.

The Flink job accepts the argument names of the Spark and Beam jobs as well as its own: `--failedOutput` for `--failed`, `--unrecoverableOutput` for `--unrecoverable` and `--batchSize` for `--batch-size`. `--batch-size` must be between 1 and 500, the most records Kinesis accepts in one PutRecords call.

Without `--failed`, failed bad rows go to `<input>/unrecovered`. Without `--unrecoverable`, unrecoverable ones go to `<failed>/unrecoverable` when `--failed` is given, and to `<input>/unrecoverable` otherwise, as with the Spark and Beam jobs. The job reads its input path including subdirectories, but skips its own outputs and checkpoint directory, so a rerun on the same input does not recover the previous run's failed rows again. Hidden files (names starting with `.` or `_`) are skipped too. Input paths are directories or files; globs such as `s3://bucket/path/*` are not expanded.

## Flink job: delivery and counts
The Flink job retries records that Kinesis throttles until they are written, and never drops a record: a request either succeeds, is retried, or fails the job. So when the job finishes successfully, every recovered event is in the output stream and directory, whichever were given. The summary names only the destinations that were given.

The Kinesis sink only fails the job on a few request errors: a missing stream, credentials that cannot be obtained, and client-side errors. Any other error is retried without end, including rejected credentials and `AccessDeniedException`. So before the run starts, the job describes the output stream with the credentials, region and endpoint the sink will use, and fails at once if that does not work. That check cannot tell whether the credentials may write to the stream: if they lack `kinesis:PutRecords`, or `kms:GenerateDataKey` on the key of an encrypted stream, the run makes no progress and `numRecordsOutErrors` on `kinesis: Writer` keeps rising, with `AccessDeniedException` or `KMSAccessDeniedException` in the task logs. Stop the run and fix the permissions.

On success the job logs a summary:

```
Recovery finished: <n> recovered and written to Kinesis stream <stream> and <directory>, <n> failed (written to <path>), <n> unrecoverable (written to <path>)
```

`failed` and `unrecoverable` are bad rows the recovery configuration could not fix. They are written to S3 and never reach Kinesis. Each input event is counted once.

Flink's metrics for the job (visible in the Flink web UI, or through a metrics reporter if the cluster configures one; the reference template does not) mean:

| Metric | Meaning |
|---|---|
| `events.recovered`, `events.failed`, `events.unrecoverable` | Events counted as they leave the recovery step, before any write. `recovered` is what was handed to the Kinesis sink, not what Kinesis accepted. |
| `numRecordsOut` on the `kinesis: Writer` operator | Records sent to Kinesis, counting each retry again. |
| `numRecordsOutErrors` on the `kinesis: Writer` operator | Records Kinesis rejected and that will be retried. |

While the job runs, records written so far is `numRecordsOut` minus `numRecordsOutErrors` on `kinesis: Writer`. This relies on Kinesis reporting an accurate `FailedRecordCount` for PutRecords. It has not been verified against real Kinesis: the integration tests use LocalStack, which reports a `FailedRecordCount` of 1 for any throttled batch.

## Flink job: failures and checkpointing
Without checkpointing, which is the supported setup, a run that fails is not restarted: the step fails, and a rerun on the same input starts over and sends again every event the failed run had written, as with the Spark job. Delivery to Kinesis is at-least-once in that sense; a run that succeeds sends each event once.

`--checkpoint-interval <seconds>` and `--checkpoint-dir <path>` turn on checkpointing (both are needed; the interval is in seconds), so a failed run can be resumed with `flink run -s <checkpoint>` and Flink restarts the job from its last checkpoint after a task failure (up to 10 failures within 10 minutes). This is not supported yet, for two reasons:

- On EMR 7.13 (Flink 1.20.0), resuming from the checkpoint at which one of the job's parallel subtasks finished fails every time, with `Currently it is not supported to update the CommittableSummary for a checkpoint coming from the same subtask` ([FLINK-25920](https://issues.apache.org/jira/browse/FLINK-25920), fixed in Flink 1.20.1). With parallelism above 1, that is often the newest checkpoint, and then the run cannot be resumed.
- Events sent after the checkpoint a run continues from are sent again, so a resumed run also leaves duplicates in the stream.

## Flink job: running on EMR
`.dataflow-runner/flink-cluster.json.tmpl` and `.dataflow-runner/flink-playbook.json.tmpl` are reference configurations for dataflow-runner. A recovery has not yet completed on EMR with this setup: a first run of 0.8.0-rc1 on EMR 7.13 found an unsupported instance type and a jar that broke the JobManager on YARN, both fixed since. The job is tested with Flink's local test cluster and LocalStack. CI also runs the built jar on the `flink:1.20.0-java11` image (`flink/src/smoke/run.sh`), from gzipped local files to files, twice: with `flink run` against a session cluster, and from Flink's `lib/` in application mode, which puts the jar on the JobManager's system classpath as `flink run -m yarn-cluster` does. A jar that bundles a logging binding or Flink's own classes fails there. Neither run covers the Kinesis sink, S3 or YARN.

Permissions the cluster's instance profile needs: `kinesis:DescribeStream` (the check before the run) and `kinesis:PutRecords` on the output stream, plus `kms:GenerateDataKey` on its key if the stream is encrypted; S3 list and read on the input; S3 write on the failed, unrecoverable, directory and checkpoint paths. AWS's managed `AmazonElasticMapReduceforEC2Role` policy, used by `EMR_EC2_DefaultRole`, grants `kinesis:DescribeStream` but not `kinesis:PutRecords` (it grants the single-record `kinesis:PutRecord`).

Cluster:
- EMR 7.13.0, which ships Flink 1.20.0, the version the job is built against. Use the `Flink` application. EMR 7.9 to 7.12 also ship Flink 1.20.0, but AWS lists a known issue there: step and container logs may not be uploaded to S3 for steps running longer than 3 hours, and the job's summary is in the step log.
- Java 11. EMR 7 runs Flink on Java 17 by default, but Flink 1.20 ships Scala 2.12.7, which cannot deserialize the job's functions on Java 17: tasks fail with `IllegalArgumentException: too many arguments` in `scala.runtime.LambdaDeserializer`. The template sets Java 11 the way AWS documents it: `env.java.home`, `containerized.master.env.JAVA_HOME` and `containerized.taskmanager.env.JAVA_HOME` in `flink-conf`, and `JAVA_HOME` in `hadoop-env`, all `/usr/lib/jvm/jre-11`.
- No bootstrap action. EMR 7.13's Flink already has the S3 file system plugin the job needs for `s3a://` paths (`plugins/s3/flink-s3-fs-hadoop-1.20.0-amzn-7.jar`), and the metrics reporter plugins. Installing a second S3 plugin breaks the cluster: the JobManager stops with `Delegation token provider with service name s3-hadoop has multiple implementations`. Earlier releases published a bootstrap action that installed one; this release no longer publishes it.
- The job jar, published in each region's hosted assets bucket. The templates take that bucket as `assetsBucket`, separate from `bucket`, which holds the input, outputs and logs.
- Instances of a type EMR 7.13 supports. The template uses `m5.xlarge`: EMR 7.13 supports no m4 type and no `.large` size of m5, m6i, m6g or m7g, and a cluster on an unsupported type fails to start ("application provisioning timed out" on the master). `aws emr list-supported-instance-types --release-label emr-7.13.0` lists them per region.

Step: run the job with `command-runner.jar` and

```
flink run -m yarn-cluster -sae <jar> --input <path> --output <stream> --failed <path> --unrecoverable <path> --region <region> --resolver <base64> --config <base64>
```

`-m yarn-cluster` starts a Flink cluster on YARN for this job only (deprecated "per-job" mode, still in Flink 1.20) and waits for it to finish. So the EMR step fails when the job fails, and the summary or the failure message is in the step's log. `-sae` (`--shutdownOnAttachedExit`) shuts that Flink cluster down when the `flink run` client stops, for example when the step is cancelled, so the job does not keep writing to Kinesis (tested on EMR 7.13). `-sae` also makes the client send heartbeats, and the JobManager cancels the job if none arrives within `client.heartbeat.timeout` (3 minutes by default). On EMR 7.13 the JobManager rejects every heartbeat (`Request did not match expected format JobClientHeartbeatRequestBody`), so any job longer than about 4 minutes was cancelled. The template therefore sets `client.heartbeat.timeout` to 3650 days in `flink-conf`. Stopping the step still cancels the job, but if the client dies without running its shutdown hook (killed with `-9`, master node lost), the job keeps running until its YARN application is killed. The template also sets `classloader.check-leaked-classloader: false`, which silences a harmless `Trying to access closed classloader` stack trace the client prints after each run.

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
