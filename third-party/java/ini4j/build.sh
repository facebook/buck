#!/bin/sh

set -e

readonly tempdir=$(mktemp -d)
readonly original_dir="$PWD"
trap "rm -rf $tempdir" EXIT

echo "Using $tempdir to keep temporary files"
pushd "$tempdir"

echo "Cloning ini4j repository from Github"
git clone https://github.com/facebook/ini4j.git

pushd ini4j
git checkout 609ce1f988c8a19c4c951243228f1921e30c03b1

echo "Building ini4j"
mvn package

popd
popd

echo "Copying built artifacts to ${original_dir}"
ini4j_dir="${tempdir}/ini4j"
cp "${ini4j_dir}"/target/ini4j-0.5.5-SNAPSHOT.jar ini4j-0.5.5-SNAPSHOT.jar 
cp "${ini4j_dir}"/target/ini4j-0.5.5-SNAPSHOT-sources.jar ini4j-0.5.5-SNAPSHOT-sources.jar

