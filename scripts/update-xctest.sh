#!/bin/bash

set -x
set -euo pipefail

BUCK_ROOT=$(git rev-parse --show-toplevel)
BUCK_FBXCTEST_DIR=$BUCK_ROOT/test/com/facebook/buck/apple/testdata/fbxctest

rm -rf $BUCK_FBXCTEST_DIR/{Frameworks,bin,lib}
mkdir $BUCK_FBXCTEST_DIR/{Frameworks,bin,lib}

TMPDIR=`mktemp -d`
cd $TMPDIR

git clone https://github.com/facebook/xctool
pushd xctool
./xctool.sh || true
XCTOOL_LIB_DIR=$(ls -d build/*/*/Products/Release/lib)
cp -r $XCTOOL_LIB_DIR $BUCK_FBXCTEST_DIR
popd

git clone https://github.com/facebook/FBSimulatorControl
pushd FBSimulatorControl
./build.sh fbxctest build
cp build/Build/Products/Debug/fbxctest $BUCK_FBXCTEST_DIR/bin

./build.sh framework build
find build/Build/Products/Debug/ -type l -delete
find build/Build/Products/Debug/ -name Frameworks | xargs rm -rf
find build/Build/Products/Debug/ -name Headers | xargs rm -rf
find build/Build/Products/Debug/ -name Modules | xargs rm -rf
cp -r build/Build/Products/Debug/*.framework $BUCK_FBXCTEST_DIR/Frameworks
cp -r $BUCK_FBXCTEST_DIR/Frameworks/XCTestBootstrap.framework/Versions/A/Resources $BUCK_FBXCTEST_DIR/Frameworks/XCTestBootstrap.framework
popd
