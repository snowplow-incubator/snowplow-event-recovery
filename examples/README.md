# Designing recoveries with CLI

Recovery process can be slow and costly, when ran on a large failed event dataset.
To make the process less painful, a local small-scale iterative approach can be taken.

Before you begin, you will need the following:
- snowplow-event-recovery cli
- sample failed events text files

Keep in mind, that this tutorial is centered around the CLI and much more technical details are available in the [docs](https://docs.snowplow.io/docs/managing-data-quality/recovering-failed-events/) pages.

## Step 1: Identify the failures
In this tutorial we're going to work with a sample [bad-row](https://github.com/snowplow/snowplow-badrows) located in `examples` directory. 
There is a wide variety of them and you can inspect and the pre-designed sample config (also in `examples`) to see how that works.

The event we're trying to recover is a schema_violation. It has an invalid schema set to: `0/video_impression/jsonschema/1-0-0` in the `ue_pr` field. It is one amongst many other schema violations, so we'll need to filter it out somehow.

Recovery configuration is a json-structured map pointing schemas to its corresponding recovery flows. The flow can select a subset of schema-matching events using conditions. For the sample event we therefore want something like this:

```json
{
  // map any version of schema_violations
  "iglu:com.snowplowanalytics.snowplow.badrows/schema_violations/jsonschema/*-*-*": [{
    "name": "my-recovery-flow",
    "conditions": [{
      "op": "Test",
      "path": "$.payload.raw.parameters.ue_pr.data.schema", // check ue_pr.data.schema for
      "value": {
        "value": "iglu:0/video_impression/jsonschema/1-0-0" // this value
      }
    }],
    // ...
  }]
  //...
}
```

Now, this flow will only match for failures that have the offending schema we're trying to fix.

## Step 2: Write an initial recovery configuration

With the failing events selected we're going to fix them. In this case we only need to replace offending string in the `ue_pr` field using a `Replace` operation:

```json
{
  "steps": [{
    "op": "Replace",
    "path": "$.raw.parameters.[?(@.name=~ue_pr)].value",
    "match": "iglu:0/video_impression/jsonschema/1-0-0",
    "value": "iglu:com.snowplowanalytics.iglu/anything-a/jsonschema/1-0-0"
  }]
  // ...
}
```

## Step 3: Validate the configuration

Putting the above together, we can now define a full configuration for the sample event:

```json
{
  "schema": "iglu:com.snowplowanalytics.snowplow/recoveries/jsonschema/4-0-0",
  "data": {
    "iglu:com.snowplowanalytics.snowplow.badrows/schema_violations/jsonschema/*-*-*": [
      {
        "name": "my recovery flow",
        "conditions": [
          {
            "op": "Test",
            "path": "$.payload.raw.parameters.ue_pr.data.schema",
            "value": {
              "value": "iglu:0/video_impression/jsonschema/1-0-0"
            }
          }
        ],
        "steps": [
          {
            "op": "Replace",
            "path": "$.raw.parameters.[?(@.name=~ue_pr)].value",
            "match": "iglu:0/video_impression/jsonschema/1-0-0",
            "value": "iglu:com.snowplowanalytics.iglu/anything-a/jsonschema/1-0-0"
          }
        ]
      }
    ]
  }
}
```

And validate the config using the snowplow-event-recovery cli:

```
snowplow-event-recovery validate -c $PWD/examples/config.json 
"""ERROR! Invalid config

Instance is not valid against its schema:
* At $.iglu:com.snowplowanalytics.snowplow.badrows/schema_violations/jsonschema/*-*-*[0].name:
  - $.iglu:com.snowplowanalytics.snowplow.badrows/adapter_failures/jsonschema/*-*-*[0].name: does not match the regex pattern ^(([a-zA-Z0-9]+)(-?))+$
  - $.iglu:com.snowplowanalytics.snowplow.badrows/adapter_failures/jsonschema/*-*-*[0].name: must be at least 1 characters long"""
```
Seems like we've used an invalid name somwhere. Let's inspect where the error happens:
```
$.iglu:com.snowplowanalytics.snowplow.badrows/schema_violations/jsonschema/*-*-*[0].name:
```

Means, starting from the root (`$`), field named `iglu:com.snowplowanalytics.snowplow.badrows/schema_violations/jsonschema/*-*-*`contains an element `[0]` (first one) that's name does not follow a defined pattern: a string with dashes. Let's fix it:
``` diff
-        "name": "my recovery flow",
+        "name": "my-recovery-flow",
```

And rerun the validation:

```
snowplow-event-recovery validate -c $PWD/examples/config.json 
"OK! Config valid"
```

## Step 4: Run the config

Now that, the config is valid, we can test run it locally using the cli:

```shell
snowplow-event-recovery run --config $PWD/examples/config.json --output /tmp --input $PWD/examples

"Total Lines: 1, Recovered: 1"
"OK! Successfully ran recovery."
```

And that's it. You've just recreated collector payloads from the failures. These payloads were then verified by running them through an enrichment step (with all enrichments disabled).
The recovered events fill be written into `bad.txt` and `good.txt` files in the `--output` directry.

However, if your schemas are not available in Iglu Central, you can override iglu resolver config:
```shell
snowplow-event-recovery run --config $PWD/examples/config.json --output /tmp --input $PWD/examples --resolver $PWD/examples/resolver.json
```

And if you need to run these through additional enrichments, these can be attached like this:
```shell
snowplow-event-recovery run --config $PWD/examples/config.json --output /tmp --input $PWD/examples --resolver $PWD/examples/resolver.json --enrichments $PWD/examples/enrichments
```

Keep in mind though, that enrichments aren't currently able to use third-party resources like maxmind database or iab configurations.

## Step 5: Run large-scale recovery job

Now that you've fine-tuned the configuration the configuration can be ran at a scale as described in the [docs](https://docs.snowplow.io/docs/managing-data-quality/recovering-failed-events/).
