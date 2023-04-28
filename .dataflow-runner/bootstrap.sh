#!/bin/bash

set -euxo

sudo update-alternatives --set java /usr/lib/jvm/java-11-amazon-corretto.x86_64/bin/java
sudo mkdir -p /usr/lib/flink/plugins/s3-fs-hadoop
sudo cd /usr/lib/flink/plugins/s3-fs-hadoop
sudo curl -L https://search.maven.org/remotecontent?filepath=org/apache/flink/flink-s3-fs-hadoop/1.15.2/flink-s3-fs-hadoop-1.15.2.jar -o /usr/lib/flink/plugins/s3-fs-hadoop/flink-s3-fs-hadoop-1.15.2.jar
sudo chmod 755 /usr/lib/flink/plugins/s3-fs-hadoop/flink-s3-fs-hadoop-1.15.2.jar
sudo mkdir -p /usr/lib/flink/plugins/metrics-statsd
sudo cd /usr/lib/flink/plugins/metrics-statsd
sudo curl -L https://search.maven.org/remotecontent?filepath=org/apache/flink/flink-metrics-statsd/1.15.2/flink-metrics-statsd-1.15.2.jar -o /usr/lib/flink/plugins/metrics-statsd/flink-metrics-statsd-1.15.2.jar
sudo rpm -Uvh --force https://s3.amazonaws.com/amazoncloudwatch-agent/amazon_linux/amd64/latest/amazon-cloudwatch-agent.rpm
sudo cat > /home/hadoop/cloudwatch-agent.json <<EOF
{
    "agent": {
        "logfile": "/opt/aws/amazon-cloudwatch-agent/logs/amazon-cloudwatch-agent.log",
        "debug": false,
        "run_as_user": "cwagent"
    },
    "metrics": {
        "namespace": "snowplow/event-recovery",
        "metrics_collected": {
            "statsd": {
                "service_address": ":8125",
                "metrics_collection_interval": 1,
                "metrics_aggregation_interval": 1
            }
        },
        "aggregation_dimensions": [["InstanceId"],[]]
    }
}
EOF
sudo amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -s -c file:/home/hadoop/cloudwatch-agent.json


exit 0
