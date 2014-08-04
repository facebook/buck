#!/bin/bash

# Integration test for the exopackage installer.
# This just tests the happy path, so it's more a test of device compatibility.

set -x
set -e
set -o pipefail

cd `dirname $0`
cd `git rev-parse --show-cdup`

# Copy the test project into a temp dir, then cd into it and make it a buck project.
TMP_DIR=`mktemp -d /tmp/exopackage-text.XXXXXX`
trap "rm -rf $TMP_DIR" EXIT HUP INT TERM
cp -r test/com/facebook/buck/android/testdata/exopackage-device/* $TMP_DIR
cp buck-out/gen/src/com/facebook/buck/android/support/buck-android-support.jar $TMP_DIR
cd $TMP_DIR
touch .buckconfig

# Clear out the phone.
adb uninstall com.facebook.buck.android.agent
adb uninstall buck.exotest
adb shell rm -r /data/local/tmp/exopackage/buck.exotest

# Build and do a clean install of the app.  Launch it and capture logs.
echo '1a' > value.txt
env NO_BUCKD=1 buck install //:exotest | cat
adb logcat -c
adb shell am startservice -n buck.exotest/exotest.LogService
sleep 1
adb logcat -d adb logcat '*:S' EXOPACKAGE_TEST:V > out1.txt

# Rebuild and do an incremental install of the app.  Launch it and capture logs.
echo '2b' > value.txt
env NO_BUCKD=1 buck install //:exotest | cat
adb logcat -c
adb shell am startservice -n buck.exotest/exotest.LogService
sleep 1
adb logcat -d adb logcat '*:S' EXOPACKAGE_TEST:V > out2.txt

# Check for the old and new values in the logs.
grep 'VALUE=1a' out1.txt
grep 'VALUE=2b' out2.txt

# Clean up after ourselves.
buck uninstall //:exotest
adb uninstall com.facebook.buck.android.agent

# Celebrate!  (And show that we succeeded, because grep doesn't print error messages.)
echo GREAT_SUCCESS
