#!/bin/sh -e

# Generate starlark-annot-processor jars for Idea.
# Run this script when starlark annotation sources changed.

cd $(dirname $0)/../../../..

test -e third-party/java/bazel

buck build //third-party/java/bazel:starlark-annot \
    --out third-party/java/bazel/jars-for-idea/starlark-annot.jar
buck build //third-party/java/bazel:starlark-annot-processor-lib \
    --out third-party/java/bazel/jars-for-idea/starlark-annot-processor.jar

find third-party/java/bazel/src/main/java/net/starlark/java/annot -name '*.java' \
    | sort \
    | xargs cat \
    | shasum \
    | sed -e 's, .*,,' \
    > third-party/java/bazel/jars-for-idea/sources.sha1
