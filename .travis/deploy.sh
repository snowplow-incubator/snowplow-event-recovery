#!/bin/bash

tag=$1

cd $TRAVIS_BUILD_DIR

export TRAVIS_BUILD_RELEASE_TAG=${tag}
release-manager --config ./.travis/release_beam.yml --check-version --make-artifact --upload-artifact
release-manager --config ./.travis/release_flink.yml --check-version --make-artifact --upload-artifact
release-manager --config ./.travis/release_spark.yml --check-version --make-artifact --upload-artifact

export BINTRAY_USER=${BINTRAY_SNOWPLOW_MAVEN_USER}
export BINTRAY_PASS=${BINTRAY_SNOWPLOW_MAVEN_API_KEY}

project_version=$(sbt version -Dsbt.log.noformat=true | perl -ne 'print "$1\n" if /info.*(\d+\.\d+\.\d+[^\r\n]*)/' | tail -n 1 | tr -d '\n')
if [[ "${tag}" = *"${project_version}" ]]; then
    sbt "project core" publish
else
    echo "Tag version '${tag}' doesn't match version in scala project ('${project_version}'). Aborting!"
    exit 1
fi
