#!/bin/bash

tag=$1

cd $TRAVIS_BUILD_DIR

export TRAVIS_BUILD_RELEASE_TAG=${tag}
release-manager --config ./.travis/release_spark.yml --check-version --make-artifact --upload-artifact
release-manager --config ./.travis/release_beam.yml --check-version --make-artifact --upload-artifact
