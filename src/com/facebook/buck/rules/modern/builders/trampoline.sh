#!/bin/bash
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


# Run with -e so the script will fail if any of the steps fail.
set -e

(
function resolveList() {
  echo $1 | tr ':' '\n' | sed "s/^\([^/].*\)$/$(echo $PWD | sed 's/\//\\\//g')\/\1/" | tr '\n' ':'
}

function resolve() {
  echo $1 | sed "s/^\([^/].*\)$/$(echo $PWD | sed 's/\//\\\//g')\/\1/"
}

function getJavaPathForVersion() {
  local java_path
  java_path="java"
  echo "$java_path"
}

export BUCK_CLASSPATH=$(resolveList $BUCK_CLASSPATH)
export CLASSPATH=$(resolveList $CLASSPATH)
export BUCK_ISOLATED_ROOT=$PWD

export BUCK_PLUGIN_RESOURCES=$(resolve $BUCK_PLUGIN_RESOURCES)
export BUCK_PLUGIN_ROOT=$(resolve $BUCK_PLUGIN_ROOT)
# necessary for "isolated buck" invocations to work correctly
export BASE_BUCK_OUT_DIR=$BASE_BUCK_OUT_DIR
# this env variable is used in some rules to check if build is running using BUCK
export BUCK_BUILD_ID="RE_buck_build_id"

if [ "$BUCK_DEBUG_MODE" == "1" ]; then
  export BUCK_DEBUG_ARGS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8888"
fi

cd $1

JAVA_PATH=$(getJavaPathForVersion $BUCK_JAVA_VERSION)

$JAVA_PATH -cp $CLASSPATH \
  $BUCK_DEBUG_ARGS \
  -Xverify:none -XX:+TieredCompilation -XX:TieredStopAtLevel=1 \
  -Dpf4j.pluginsDir=$BUCK_PLUGIN_ROOT \
  -Dbuck.module.resources=$BUCK_PLUGIN_RESOURCES \
  -Dbuck.base_buck_out_dir=$BASE_BUCK_OUT_DIR \
  com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper \
  com.facebook.buck.rules.modern.builders.OutOfProcessIsolatedBuilder \
  $BUCK_ISOLATED_ROOT "$@"
)
