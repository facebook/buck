#!/usr/bin/env bash -ex
for f in $(git ls-files -- *.thrift); do
  /usr/local/bin/thrift --gen java:generated_annotations=undated -out src-gen/ $f
done
