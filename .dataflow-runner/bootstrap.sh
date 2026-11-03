#!/bin/bash
# Installs Flink's S3 file system plugin, needed for the job's s3a:// paths.
set -euxo pipefail

sudo mkdir -p /usr/lib/flink/plugins/s3-fs-hadoop
sudo curl -fsSL --retry 5 \
  'https://repo1.maven.org/maven2/org/apache/flink/flink-s3-fs-hadoop/1.20.0/flink-s3-fs-hadoop-1.20.0.jar' \
  -o /usr/lib/flink/plugins/s3-fs-hadoop/flink-s3-fs-hadoop-1.20.0.jar
sudo chmod 755 /usr/lib/flink/plugins/s3-fs-hadoop/flink-s3-fs-hadoop-1.20.0.jar
