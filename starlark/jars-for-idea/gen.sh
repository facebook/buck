#!/bin/sh -e
# Copyright (c) Facebook, Inc. and its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Generate starlark-annot-processor jars for Idea.
# Run this script when starlark annotation sources changed.

cd "$(dirname "$0")/../.."

test -e starlark

buck build //starlark:starlark-annot \
    --out starlark/jars-for-idea/starlark-annot.jar
buck build //starlark:starlark-annot-processor-lib \
    --out starlark/jars-for-idea/starlark-annot-processor.jar

find starlark/src/main/java/net/starlark/java/annot -name '*.java' \
    | sort \
    | xargs cat \
    | shasum \
    | sed -e 's, .*,,' \
    > starlark/jars-for-idea/sources.sha1
