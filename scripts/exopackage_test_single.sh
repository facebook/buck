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


# Integration test for the exopackage installer.
# This just tests the happy path, so it's more a test of device compatibility.

set -x
set -e
set -o pipefail

# Just check that we can communicate with a device/emulator.
adb get-state

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

if [ "$1" == "--debug" ]; then
  shift
  TMP_DIR='/tmp/exopackage-test'
  rm -rf $TMP_DIR/*
  mkdir -p $TMP_DIR
else
  TMP_DIR=`mktemp -d /tmp/exopackage-test.XXXXXX`
  trap "rm -rf $TMP_DIR" EXIT HUP INT TERM
fi

# Copy the test project into a temp dir, then cd into it and make it a buck project.
cp -r test/com/facebook/buck/android/testdata/exopackage-device/* $TMP_DIR
buck build --out $TMP_DIR/buck.pex buck
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
com.facebook.buck.android.exopackage.level=FINER
EOF
export NO_BUCKD=1

# Clear out the phone.
adb uninstall com.facebook.buck.android.agent
adb uninstall buck.exotest
adb uninstall buck.exotest.meta
adb shell 'rm -r /data/local/tmp/exopackage/buck.exotest || rm -f -r /data/local/tmp/exopackage/buck.exotest'

EXP_APP_NAME='Exo Test App'

OUT_COUNT=0
function installAndLaunch() {
  ./buck.pex install ${1:-//:exotest} | cat
  adb logcat -c
  adb shell am start -n buck.exotest/exotest.LogActivity
  adb shell am start -n buck.exotest.meta/.ExoMetaLogActivity
  SECONDARY_DEX_INSTALLED=$(cat buck-out/log/build.trace | \
    jq -r '[ .[] | select(.name == "install_secondary_dex") | select(.ph == "B") ] | length')
  NATIVE_LIBS_INSTALLED=$(cat buck-out/log/build.trace | \
    jq -r '[ .[] | select(.name == "install_native_library") | select(.ph == "B") ] | length')
  RESOURCE_APKS_INSTALLED=$(cat buck-out/log/build.trace | \
    jq -r '[ .[] | select(.name == "install_resources") | select(.ph == "B") ] | length')
  MODULAR_DEX_INSTALLED=$(cat buck-out/log/build.trace | \
      jq -r '[ .[] | select(.name == "install_modular_dex") | select(.ph == "B") ] | length')
  sleep 1
  adb logcat -d '*:S' EXOPACKAGE_TEST:V EXOPACKAGE_TEST_META:V > out.txt
  cp out.txt out$((++OUT_COUNT)).txt

  # Check for values in the logs.
  grep -q "VALUE=$EXP_JAVA" out.txt || (cat out.txt && false)
  grep -q "NATIVE_ONE=$EXP_CPP1" out.txt || (cat out.txt && false)
  grep -q "NATIVE_TWO=$EXP_CPP2" out.txt || (cat out.txt && false)
  grep -q "RESOURCE=$EXP_RESOURCE" out.txt || (cat out.txt && false)
  grep -q "IMAGE=$EXP_IMAGE" out.txt || (cat out.txt && false)
  grep -q "ASSET=$EXP_ASSET" out.txt || (cat out.txt && false)
  grep -q "ASSET_TWO=$EXP_ASSET2" out.txt || (cat out.txt && false)
  grep -q "MODULE_ONE=$EXP_MODULE" out.txt || (cat out.txt && false)

  grep -q "META_ICON=.*_.*" out.txt || (cat out.txt && false)
  grep -q "META_NAME=$EXP_APP_NAME" out.txt || (cat out.txt && false)
  grep -q "META_DATA=<item0,item1,item2>" out.txt || (cat out.txt && false)
}

function create_image() {
  mkdir -p res/drawable-nodpi
  convert -size ${1}x${1} xc:none res/drawable-nodpi/image.png
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
  sedInPlace "s/\(string name=\"hello\">\)[^<]*/\1res_$1/" res/values/strings.xml
  EXP_RESOURCE=res_$1
}

function edit_java() {
  echo "$1" > value.txt
  EXP_JAVA=$1
}

function edit_module() {
  echo "$1" > module_value.txt
  EXP_MODULE=$1
}

function edit_cpp1() {
  sedInPlace "s/one_../one_$1/" jni/one/one.c
  EXP_CPP1=one_$1
}

function edit_cpp2() {
  sedInPlace "s/two_../two_$1/" jni/two/two.c
  EXP_CPP2=two_$1
}

function change_label() {
  sedInPlace "s/$EXP_APP_NAME/$1/" res/values/strings.xml
  sedInPlace "s/$EXP_APP_NAME/$1/" res-real-name/values/strings.xml
  EXP_APP_NAME=$1
}

./buck.pex install //app-meta-tool:exometa

# Build and do a clean install of the app.  Launch it and capture logs.
create_image 1
edit_java '1a'
edit_module '1a'

installAndLaunch

# Check for full install.
test "$SECONDARY_DEX_INSTALLED" = 1
test "$NATIVE_LIBS_INSTALLED" = 2
# 1: exo-resources 2: exo-assets 3: exo-java-resources
test "$RESOURCE_APKS_INSTALLED" = 3
test "$MODULAR_DEX_INSTALLED" = 1

change_label 'Exo App New Label'
installAndLaunch
test "$SECONDARY_DEX_INSTALLED" = 0
test "$NATIVE_LIBS_INSTALLED" = 0
test "$RESOURCE_APKS_INSTALLED" = 1
test "$MODULAR_DEX_INSTALLED" = 0

# Change java code and do an incremental install of the app.  Launch it and capture logs.
edit_java '2b'
installAndLaunch

# Check for incremental java install.
test "$SECONDARY_DEX_INSTALLED" = 1
test "$NATIVE_LIBS_INSTALLED" = 0
test "$RESOURCE_APKS_INSTALLED" = 0
test "$MODULAR_DEX_INSTALLED" = 0
# Check for the new values in the logs.

# Change modular code and do an incremental install of the app.  Launch it and capture logs.
edit_module '2bpart2'
installAndLaunch

# Check for incremental module install.
test "$SECONDARY_DEX_INSTALLED" = 0
test "$NATIVE_LIBS_INSTALLED" = 0
test "$RESOURCE_APKS_INSTALLED" = 0
test "$MODULAR_DEX_INSTALLED" = 1
# Check for the new values in the logs.

# Change one of the native libraries, do an incremental install and capture logs.
edit_cpp1 3c
installAndLaunch

# Check for incremental native install.
test "$SECONDARY_DEX_INSTALLED" = 0
test "$NATIVE_LIBS_INSTALLED" = 1
test "$RESOURCE_APKS_INSTALLED" = 0
test "$MODULAR_DEX_INSTALLED" = 0


# Change both native and java code and do an incremental build.
edit_java '4d'
edit_cpp2 4d
installAndLaunch

# Check for incremental java and native install.
test "$SECONDARY_DEX_INSTALLED" = 1
test "$NATIVE_LIBS_INSTALLED" = 1
test "$RESOURCE_APKS_INSTALLED" = 0
test "$MODULAR_DEX_INSTALLED" = 0

# Change an image and do an incremental build.
create_image 5
installAndLaunch
test "$SECONDARY_DEX_INSTALLED" = 0
test "$NATIVE_LIBS_INSTALLED" = 0
test "$RESOURCE_APKS_INSTALLED" = 1
test "$MODULAR_DEX_INSTALLED" = 0

# Change an asset and do an incremental build.
edit_asset '6f'
installAndLaunch
test "$SECONDARY_DEX_INSTALLED" = 0
test "$NATIVE_LIBS_INSTALLED" = 0
test "$RESOURCE_APKS_INSTALLED" = 1
test "$MODULAR_DEX_INSTALLED" = 0

# Change a resource and do an incremental build.
edit_resource '7g'
installAndLaunch
test "$SECONDARY_DEX_INSTALLED" = 0
test "$NATIVE_LIBS_INSTALLED" = 0
test "$RESOURCE_APKS_INSTALLED" = 1
test "$MODULAR_DEX_INSTALLED" = 0

# Change all resources and do an incremental build.
create_image 8
edit_asset2 '8h'
edit_resource '8h'
installAndLaunch
test "$SECONDARY_DEX_INSTALLED" = 0
test "$NATIVE_LIBS_INSTALLED" = 0
test "$RESOURCE_APKS_INSTALLED" = 2
test "$MODULAR_DEX_INSTALLED" = 0


# Change everything and do a incremental build.
edit_java '9i'
edit_module '9i'
edit_cpp2 '9i'
create_image 9
edit_asset '9i'
edit_resource '9i'
installAndLaunch
test "$SECONDARY_DEX_INSTALLED" = 1
test "$NATIVE_LIBS_INSTALLED" = 1
test "$RESOURCE_APKS_INSTALLED" = 2
test "$MODULAR_DEX_INSTALLED" = 1

# Change everything and do a no-exopackage incremental build.
edit_java '10j'
edit_cpp2 '10j'
create_image 10
edit_resource '10j'
edit_asset '10j'
installAndLaunch //:exotest-noexo

# Check for no exo install.
test "$SECONDARY_DEX_INSTALLED" = 0
test "$NATIVE_LIBS_INSTALLED" = 0
test "$RESOURCE_APKS_INSTALLED" = 0
test "$MODULAR_DEX_INSTALLED" = 0


# Clean up after ourselves.
./buck.pex uninstall //:exotest-noexo
./buck.pex uninstall //app-meta-tool:exometa
adb uninstall com.facebook.buck.android.agent

# Celebrate!  (And show that we succeeded, because grep doesn't print error messages.)
echo -e "\033[42;37m\033[1mGREAT_SUCCESS\033[0m"
