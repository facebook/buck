#!/bin/bash -e
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

# Start multitenant service locally and load provided parse state file

# Usage 1 (from file): ./scripts/multitenant/run.sh corpus@parse_state.json@/path/to/checkout
# Usage 2 (from HTTP): ./scripts/multitenant/run.sh corpus@http://www.some.site/parse_state.json@/path/to/checkout

# some useful Java command line options:

# memory: -J-Xmx16G
# debugging: -J-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=8888

TARGET=$(buck build //src/com/facebook/buck/multitenant/runner:runner --show-output | awk '{print $2}')
kotlin -cp $TARGET \
-J-Xmx16G \
-J-XX:+UseG1GC \
-J-XX:StringTableSize=1000003 \
com.facebook.buck.multitenant.runner.MainKt \
"$@"

