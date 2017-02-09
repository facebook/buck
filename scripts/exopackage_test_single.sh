#!/bin/bash

# Integration test for the exopackage installer.
# This just tests the happy path, so it's more a test of device compatibility.

set -x
set -e
set -o pipefail

function sedInPlace() {
  case "$(uname -s)" in
    Darwin)
      sed -i '' -e "$1" "$2"
      ;;
    Linux)
      sed "$1" -i "$2"
      ;;
    *)
      echo "Unsupported OS name '$(uname -s)' for running sed."
      exit 1
  esac
}

cd `dirname $0`
cd `git rev-parse --show-cdup`

# Copy the test project into a temp dir, then cd into it and make it a buck project.
TMP_DIR=`mktemp -d /tmp/exopackage-test.XXXXXX`
trap "rm -rf $TMP_DIR" EXIT HUP INT TERM
cp -r test/com/facebook/buck/android/testdata/exopackage-device/* $TMP_DIR
buck build --out $TMP_DIR/buck-android-support.jar buck-android-support
cd $TMP_DIR
for BUCKFILE in `find . -name BUCK.fixture` ; do
  mv $BUCKFILE ${BUCKFILE%%.fixture}
done
cat >.buckconfig <<EOF
[java]
  source_level = 7
  target_level = 7
[ndk]
  cxx_runtime = system
  cpu_abis = armv7, x86
EOF
cat >.bucklogging.properties <<EOF
com.facebook.buck.android.ExopackageInstaller.level=FINER
EOF
export NO_BUCKD=1

# Clear out the phone.
adb uninstall com.facebook.buck.android.agent
adb uninstall buck.exotest
adb shell 'rm -r /data/local/tmp/exopackage/buck.exotest || rm -f -r /data/local/tmp/exopackage/buck.exotest'

OUT_COUNT=0
function installAndLaunch() {
  buck install ${1:-//:exotest} | cat
  adb logcat -c
  adb shell am start -n buck.exotest/exotest.LogActivity
  SECONDARY_DEX_INSTALLED=$(cat buck-out/log/build.trace | \
    jq -r '[ .[] | select(.name == "install_secondary_dex") | select(.ph == "B") ] | length')
  NATIVE_LIBS_INSTALLED=$(cat buck-out/log/build.trace | \
    jq -r '[ .[] | select(.name == "install_native_library") | select(.ph == "B") ] | length')
  sleep 1
  adb logcat -d '*:S' EXOPACKAGE_TEST:V > out.txt
  cp out.txt out$((++OUT_COUNT)).txt
}

function create_image() {
  mkdir -p res/drawable
  convert -size ${1}x${1} xc:none res/drawable/image.png
}

function edit_asset() {
  echo "asset_$1" > assets/asset.txt
}

function edit_asset2() {
  echo "asset2_$1" > assets2/asset2.txt
}

function edit_resource() {
  sedInPlace "s/\(string name=\"hello\">\)[^<]*/\1$1/" res/values/strings.xml
}

# Build and do a clean install of the app.  Launch it and capture logs.
echo '1a' > value.txt
create_image 1

installAndLaunch

# Check for full install.
test "$SECONDARY_DEX_INSTALLED" = 1
test "$NATIVE_LIBS_INSTALLED" = 2
# Check for values in the logs.
grep 'VALUE=1a' out.txt
grep 'NATIVE_ONE=one_1a' out.txt
grep 'NATIVE_TWO=two_1a' out.txt


# Change java code and do an incremental install of the app.  Launch it and capture logs.
echo '2b' > value.txt
installAndLaunch

# Check for incremental java install.
test "$SECONDARY_DEX_INSTALLED" = 1
test "$NATIVE_LIBS_INSTALLED" = 0
# Check for the new values in the logs.
grep 'VALUE=2b' out.txt


# Change one of the native libraries, do an incremental install and capture logs.
sedInPlace s/one_1a/one_3c/ jni/one/one.c
installAndLaunch

# Check for incremental native install.
test "$SECONDARY_DEX_INSTALLED" = 0
test "$NATIVE_LIBS_INSTALLED" = 1
# Check for the new values in the logs.
grep 'NATIVE_ONE=one_3c' out.txt
grep 'NATIVE_TWO=two_1a' out.txt


# Change both native and java code and do an incremental build.
echo '4d' > value.txt
sedInPlace s/two_1a/two_4d/ jni/two/two.c
installAndLaunch

# Check for incremental java and native install.
test "$SECONDARY_DEX_INSTALLED" = 1
test "$NATIVE_LIBS_INSTALLED" = 1
# Check for the new values in the logs.
grep 'VALUE=4d' out.txt
grep 'NATIVE_ONE=one_3c' out.txt
grep 'NATIVE_TWO=two_4d' out.txt


# Change both native and java code and do a no-exopackage incremental build.
echo '5e' > value.txt
sedInPlace s/two_4d/two_5e/ jni/two/two.c
installAndLaunch //:exotest-noexo

# Check for no exo install.
test "$SECONDARY_DEX_INSTALLED" = 0
test "$NATIVE_LIBS_INSTALLED" = 0
# Check for the new values in the logs.
grep 'VALUE=5e' out.txt
grep 'NATIVE_ONE=one_3c' out.txt
grep 'NATIVE_TWO=two_5e' out.txt


# Clean up after ourselves.
buck uninstall //:exotest
adb uninstall com.facebook.buck.android.agent

# Celebrate!  (And show that we succeeded, because grep doesn't print error messages.)
echo -e "\033[42;37m\033[1mGREAT_SUCCESS\033[0m"
