#!/bin/sh

set -e

readonly tempdir=$(mktemp -d)
readonly original_dir="$PWD"
#trap "rm -rf $tempdir" EXIT
echo $tempdir
svn checkout https://svn.code.sf.net/p/ini4j/code/trunk -r 205 "$tempdir"
pushd "$tempdir"
svn patch "$original_dir"/patches.diff
mvn package
popd
cp "${tempdir}"/target/ini4j-0.5.5-SNAPSHOT.jar ini4j-0.5.5-SNAPSHOT.jar 
