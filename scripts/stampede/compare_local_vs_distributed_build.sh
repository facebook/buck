#!/bin/bash

set -x # print command trace before running
set -e # exit immediately if command fails
set -v # print shell lines as read

if [ -z "$1" ]
  then
    echo "Please invoke with format: <script.sh> <buck build target>"
    exit -1
fi

TARGET=$1
SCRIPTS_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
TEMP_DIR=`mktemp -d`
BUCK_OUT_DIR=`pwd`/buck-out
LOCAL_BUILD_TEMP_DIR="$TEMP_DIR/local_buck_out"
DIST_BUILD_TEMP_DIR="$TEMP_DIR/dist_buck_out"

mkdir "$LOCAL_BUILD_TEMP_DIR"
mkdir "$DIST_BUILD_TEMP_DIR"

rm -rf ./buck-out
buck build "$TARGET" --config=cache.mode=dir --deep
cp -r "$BUCK_OUT_DIR"/. "$LOCAL_BUILD_TEMP_DIR"

rm -rf ./buck-out
buck build "$TARGET" --distributed --deep
cp -r "$BUCK_OUT_DIR"/. "$DIST_BUILD_TEMP_DIR"

echo Copied local buck-out to "$LOCAL_BUILD_TEMP_DIR"
echo Copied distributed buck-out to "$DIST_BUILD_TEMP_DIR"
echo Results dir: "$TEMP_DIR"

echo Running diff between local and distributed builds
python "$SCRIPTS_DIR"/../diff_buck_out.py "$LOCAL_BUILD_TEMP_DIR" \
"$DIST_BUILD_TEMP_DIR" > "$TEMP_DIR"/buck_out_diff.txt
python "$SCRIPTS_DIR"/diff_machine_log_rule_keys.py "$LOCAL_BUILD_TEMP_DIR" \
"$DIST_BUILD_TEMP_DIR" > "$TEMP_DIR"/rule_key_diff.txt
