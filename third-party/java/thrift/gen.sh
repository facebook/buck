#!/usr/bin/env bash -ex
# TODO(alisdair): update coordinator.thrift to be generated with current thrift and un-blacklist it.
for f in $(git ls-files -- *.thrift | grep -v coordinator.thrift); do
  /usr/local/bin/thrift --gen java:generated_annotations=undated -out src-gen/ $f
done
