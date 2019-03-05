#!/bin/sh
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

set -eux

# We export TERM=dumb to hide Buck's SuperConsole
# because otherwise Travis thinks our logs are too long.
export TERM=dumb

ant

# Use Buck to build first because its output is more
# conducive to diagnosing failed builds than Ant's is.
./bin/buck build buck || { cat buck-out/log/buck-0.log; exit 1; }

# There are only two cores in the containers, but Buck thinks there are 12
# and tries to use all of them.  As a result, we seem to have our test
# processes killed if we do not limit our threads.
export BUCK_NUM_THREADS=3

if [ "$CI_ACTION" = "build" ]; then
  # Make sure that everything builds in case a library is not covered by a test.
  ./bin/buck build --num-threads=$BUCK_NUM_THREADS src/... test/...
fi

if [ "$CI_ACTION" = "unit" ]; then
  ./bin/buck test --num-threads=$BUCK_NUM_THREADS --all --test-selectors '!.*[Ii]ntegration.*'
fi

if [ "$CI_ACTION" = "integration" ]; then
  ./bin/buck test --num-threads=$BUCK_NUM_THREADS --all --filter '^(?!(com.facebook.buck.android|com.facebook.buck.jvm.java)).*[Ii]ntegration.*'
fi

if [ "$CI_ACTION" = "heavy_integration" ]; then
  ./bin/buck build --num-threads=$BUCK_NUM_THREADS //test/com/facebook/buck/android/... //test/com/facebook/buck/jvm/java/...
  ./bin/buck test  --num-threads=1 //test/com/facebook/buck/android/... //test/com/facebook/buck/jvm/java/... --filter '.*[Ii]ntegration.*'
fi

if [ "$CI_ACTION" = "android_ndk_15" ] || [ "$CI_ACTION" = "android_ndk_16" ] || [ "$CI_ACTION" = "android_ndk_17" ] || [ "$CI_ACTION" = "android_ndk_18" ]; then
  ./bin/buck build --num-threads=$BUCK_NUM_THREADS //test/com/facebook/buck/android/...
  ./bin/buck test  --num-threads=1 //test/com/facebook/buck/android/... --filter '.*[Ii]ntegration.*'
fi

if [ "$CI_ACTION" = "ant" ]; then
  # Run all the other checks with ant.
  ant travis

  ./scripts/travisci_test_java_file_format
fi
