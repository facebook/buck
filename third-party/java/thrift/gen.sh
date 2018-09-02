#!/usr/bin/env bash -ex
for f in $(git ls-files -- *.thrift | grep -v "modern/builders/thrift"); do
  /usr/local/bin/thrift --gen java:generated_annotations=undated -out src-gen/ $f
done
