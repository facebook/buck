#!/bin/bash

# Integration test for the exopackage installer.
# This just tests the happy path, so it's more a test of device compatibility.

set -x
set -e
set -o pipefail

function sedInPlace() {
  case "$(uname -s)" in
    Darwin)
      sed -i '' -e $1 $2
      ;;
    Linux)
      sed $1 -i $2
      ;;
    *)
      echo "Unsupported OS name '$(uname -s)' for running sed."
      exit 1
  esac
}

cd `dirname $0`
cd `git rev-parse --show-cdup`

# Copy the test project into a temp dir, then cd into it and make it a buck project.
TMP_DIR=`mktemp -d /tmp/exopackage-text.XXXXXX`
trap "rm -rf $TMP_DIR" EXIT HUP INT TERM
cp -r test/com/facebook/buck/android/testdata/exopackage-device/* $TMP_DIR
cp buck-out/gen/src/com/facebook/buck/android/support/buck-android-support.jar $TMP_DIR
cd $TMP_DIR
touch .buckconfig
export NO_BUCKD=1

# Clear out the phone.
adb uninstall com.facebook.buck.android.agent
adb uninstall buck.exotest
adb shell rm -r /data/local/tmp/exopackage/buck.exotest

# Build and do a clean install of the app.  Launch it and capture logs.
echo '1a' > value.txt

buck install //:exotest | cat
adb logcat -c
adb shell am start -n buck.exotest/exotest.LogActivity
sleep 1
adb logcat -d adb logcat '*:S' EXOPACKAGE_TEST:V > out1.txt

# Check for values in the logs.
grep 'VALUE=1a' out1.txt
grep 'NATIVE_ONE=one_1a' out1.txt
grep 'NATIVE_TWO=two_1a' out1.txt


# Change java code and do an incremental install of the app.  Launch it and capture logs.
echo '2b' > value.txt
buck install //:exotest | cat
adb logcat -c
adb shell am start -n buck.exotest/exotest.LogActivity
sleep 1
adb logcat -d adb logcat '*:S' EXOPACKAGE_TEST:V > out2.txt

# Check for the new values in the logs.
grep 'VALUE=2b' out2.txt


# Change one of the native libraries, do an incremental install and capture logs.
sedInPlace s/one_1a/one_3c/ jni/one/one.c
buck install //:exotest | cat
adb logcat -c
adb shell am start -n buck.exotest/exotest.LogActivity
sleep 1
adb logcat -d adb logcat '*:S' EXOPACKAGE_TEST:V > out3.txt

# Check for the new values in the logs.
grep 'NATIVE_ONE=one_3c' out3.txt
grep 'NATIVE_TWO=two_1a' out3.txt


# Change both native and java code and do an incremental build.
echo '4d' > value.txt
sedInPlace s/two_1a/two_4d/ jni/two/two.c
buck install //:exotest | cat
adb logcat -c
adb shell am start -n buck.exotest/exotest.LogActivity
sleep 1
adb logcat -d adb logcat '*:S' EXOPACKAGE_TEST:V > out4.txt

# Check for the new values in the logs.
grep 'VALUE=4d' out4.txt
grep 'NATIVE_ONE=one_3c' out4.txt
grep 'NATIVE_TWO=two_4d' out4.txt


# Change both native and java code and do a no-exopackage incremental build.
echo '5e' > value.txt
sedInPlace s/two_4d/two_5e/ jni/two/two.c
buck install //:exotest-noexo | cat
adb logcat -c
adb shell am start -n buck.exotest/exotest.LogActivity
sleep 1
adb logcat -d adb logcat '*:S' EXOPACKAGE_TEST:V > out5.txt

# Check for the new values in the logs.
grep 'VALUE=5e' out5.txt
grep 'NATIVE_ONE=one_3c' out5.txt
grep 'NATIVE_TWO=two_5e' out5.txt


# Clean up after ourselves.
buck uninstall //:exotest
adb uninstall com.facebook.buck.android.agent

# Celebrate!  (And show that we succeeded, because grep doesn't print error messages.)
echo -e '\E[42;37m'"\033[1mGREAT_SUCCESS\033[0m"
