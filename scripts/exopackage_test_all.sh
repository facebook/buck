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

DEVICES=`adb devices | sed -rne '2,$s/^([^[:space:]]+).*/\1/p'`
for DEV in $DEVICES ; do
  RESULT="$RESULT$DEV"
  if ANDROID_SERIAL=$DEV ./scripts/exopackage_test_single.sh ; then
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
