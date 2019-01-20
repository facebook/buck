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


# Wrapper script that runs the exopackage installer test on all connected
# devices
set -x
set -e
set -o pipefail

cd `dirname $0`
cd `git rev-parse --show-cdup`

RESULT=""
EXIT_CODE="0"

DEVICES=`adb devices | sed -Ene '2,$s/^([^[:space:]]+).*/\1/p'`
for DEV in $DEVICES ; do
  export ANDROID_SERIAL=$DEV
  ANDROID_VERSION=`adb shell getprop ro.build.version.release | tr -d '\r\n'`
  RESULT="$RESULT$DEV $ANDROID_VERSION"
  if ./scripts/exopackage_test_single.sh; then
    RESULT="$RESULT SUCCESS^"
  else
    RESULT="$RESULT FAILURE^"
    EXIT_CODE=1
  fi
done

set +x
echo
echo $RESULT | tr ^ '\n'
exit $EXIT_CODE
