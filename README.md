Snowplow Event Recovery
===========================

This repository contains the codebases used for recovering bad rows emitted by a Snowplow pipeline.

The different Snowplow pipelines being all non-lossy, if something goes wrong during, for example, schema validation or enrichment the payloads (alongside the errors that happened) are stored into a bad rows storage solution, be it a data stream or object storage, instead of being discarded.

The goal of recovery is to fix the payloads contained in these bad rows so that they are ready to be processed successfully by a Snowplow enrichment platform.

For detailed documentation see [docs.snowplowanalytics.com][techdocs]

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
- Spark
- Flink (experimental)

# Extending recovery
There are several extension points for recovery: Steps, Conditions or additional [BadRow][badrows] types.

## Find out more

| Technical Docs              | Setup Guide           |
|-----------------------------|-----------------------|
| ![i1][techdocs-image]       | ![i2][setup-image]    |
| [Technical Docs][techdocs]  | [Setup Guide][setup]  |

## Copyright and license

Copyright 2018-2020 Snowplow Analytics Ltd.

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
[techdocs]: https://docs.snowplowanalytics.com/docs/snowplow-event-recovery/
