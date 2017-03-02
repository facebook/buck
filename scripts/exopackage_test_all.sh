#!/bin/bash

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
