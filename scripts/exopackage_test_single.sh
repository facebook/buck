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

  # Check for values in the logs.
  grep "VALUE=$EXP_JAVA" out.txt || (cat out.txt && false)
  grep "NATIVE_ONE=$EXP_CPP1" out.txt || (cat out.txt && false)
  grep "NATIVE_TWO=$EXP_CPP2" out.txt || (cat out.txt && false)
  grep "RESOURCE=$EXP_RESOURCE" out.txt || (cat out.txt && false)
  grep "IMAGE=$EXP_IMAGE" out.txt || (cat out.txt && false)
  grep "ASSET=$EXP_ASSET" out.txt || (cat out.txt && false)
}

function create_image() {
  mkdir -p res/drawable
  convert -size ${1}x${1} xc:none res/drawable/image.png
  EXP_IMAGE=png_${1}_${1}
}

function edit_asset() {
  echo "asset_$1" > assets/asset.txt
  EXP_ASSET=asset_$1
}

function edit_asset2() {
  echo "asset2_$1" > assets2/asset2.txt
  EXP_ASSET2=asset2_$1
}

function edit_resource() {
  sedInPlace "s/\(string name=\"hello\">\)[^<]*/\1$1/" res/values/strings.xml
  EXP_RESOURCE=res_$1
}

function edit_java() {
  echo "$1" > value.txt
  EXP_JAVA=$1
}

function edit_cpp1() {
  sedInPlace "s/one_../one_$1/" jni/one/one.c
  EXP_CPP1=one_$1
}

function edit_cpp2() {
  sedInPlace "s/two_../two_$1/" jni/two/two.c
  EXP_CPP2=two_$1
}

# Build and do a clean install of the app.  Launch it and capture logs.
create_image 1
edit_java '1a'

installAndLaunch

# Check for full install.
test "$SECONDARY_DEX_INSTALLED" = 1
test "$NATIVE_LIBS_INSTALLED" = 2

# Change java code and do an incremental install of the app.  Launch it and capture logs.
edit_java '2b'
installAndLaunch

# Check for incremental java install.
test "$SECONDARY_DEX_INSTALLED" = 1
test "$NATIVE_LIBS_INSTALLED" = 0
# Check for the new values in the logs.

# Change one of the native libraries, do an incremental install and capture logs.
edit_cpp1 3c
installAndLaunch

# Check for incremental native install.
test "$SECONDARY_DEX_INSTALLED" = 0
test "$NATIVE_LIBS_INSTALLED" = 1


# Change both native and java code and do an incremental build.
edit_java '4d'
edit_cpp2 4d
installAndLaunch

# Check for incremental java and native install.
test "$SECONDARY_DEX_INSTALLED" = 1
test "$NATIVE_LIBS_INSTALLED" = 1


# Change both native and java code and do a no-exopackage incremental build.
edit_java '5e'
edit_cpp2 5e
installAndLaunch //:exotest-noexo

# Check for no exo install.
test "$SECONDARY_DEX_INSTALLED" = 0
test "$NATIVE_LIBS_INSTALLED" = 0


# Clean up after ourselves.
buck uninstall //:exotest
adb uninstall com.facebook.buck.android.agent

# Celebrate!  (And show that we succeeded, because grep doesn't print error messages.)
echo -e "\033[42;37m\033[1mGREAT_SUCCESS\033[0m"
