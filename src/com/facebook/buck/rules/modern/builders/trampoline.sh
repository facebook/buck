#!/bin/bash
# Copyright 2018-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.
# In remote execution, a binary is run at the root directory. For Buck, this root directory may not
# be the same as the build root (the root directory is the common ancestor of all the present
# cells).
# This trampoline is used to run the buck process in the correct subdirectory.
# It also absolutizes the buck classpath and the bootstrap classpath.


# Run with -e so the script will fail if any of the steps fail.
set -e

(
function resolveList() {
  echo $1 | tr ':' '\n' | sed "s/^\([^/].*\)$/$(echo $PWD | sed 's/\//\\\//g')\/\1/" | tr '\n' ':'
}

function resolve() {
  echo $1 | sed "s/^\([^/].*\)$/$(echo $PWD | sed 's/\//\\\//g')\/\1/"
}

export BUCK_CLASSPATH=$(resolveList $BUCK_CLASSPATH)
export CLASSPATH=$(resolveList $CLASSPATH)
export BUCK_ISOLATED_ROOT=$PWD

export BUCK_PLUGIN_RESOURCES=$(resolve $BUCK_PLUGIN_RESOURCES)
export BUCK_PLUGIN_ROOT=$(resolve $BUCK_PLUGIN_ROOT)

cd $1

java -cp $CLASSPATH \
  -Xverify:none -XX:+TieredCompilation -XX:TieredStopAtLevel=1 \
  -Dpf4j.pluginsDir=$BUCK_PLUGIN_ROOT \
  -Dbuck.module.resources=$BUCK_PLUGIN_RESOURCES \
  com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper \
  com.facebook.buck.rules.modern.builders.OutOfProcessIsolatedBuilder \
  $BUCK_ISOLATED_ROOT $1 $2
)

