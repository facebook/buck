#!/usr/bin/env bash -ex
for f in $(git ls-files -- *.thrift); do
  /usr/local/bin/thrift --gen java -out src-gen/ $f
done
