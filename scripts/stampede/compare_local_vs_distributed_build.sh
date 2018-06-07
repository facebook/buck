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
TEMP_DIR_BASE=~/local/tmp
TEMP_DIR=`mktemp -d`
mkdir -p $TEMP_DIR_BASE
mv $TEMP_DIR $TEMP_DIR_BASE
TEMP_DIR=$TEMP_DIR_BASE/`basename $TEMP_DIR`

BUCK_OUT_DIR=`pwd`/buck-out
LOCAL_BUILD_TEMP_DIR="$TEMP_DIR/local_buck_out"
DIST_BUILD_TEMP_DIR="$TEMP_DIR/dist_buck_out"

rm -rf ./buck-out
buck build "$TARGET" --config=cache.mode=dir --deep -c stampede.log_materialization_enabled=true
mv "$BUCK_OUT_DIR" "$LOCAL_BUILD_TEMP_DIR"

buck build "$TARGET" --distributed --deep -c stampede.log_materialization_enabled=true
mv "$BUCK_OUT_DIR" "$DIST_BUILD_TEMP_DIR"

echo Moved local buck-out to "$LOCAL_BUILD_TEMP_DIR"
echo Moved distributed buck-out to "$DIST_BUILD_TEMP_DIR"
echo Results dir: "$TEMP_DIR"

echo Running diff between local and distributed builds
python "$SCRIPTS_DIR"/../diff_buck_out.py "$LOCAL_BUILD_TEMP_DIR" \
"$DIST_BUILD_TEMP_DIR" > "$TEMP_DIR"/buck_out_diff.txt
python "$SCRIPTS_DIR"/diff_machine_log_rule_keys.py "$LOCAL_BUILD_TEMP_DIR" \
"$DIST_BUILD_TEMP_DIR" > "$TEMP_DIR"/rule_key_diff.txt
