#!/bin/bash
#
# Copyright 2014-present Facebook, Inc.
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
#
# Meant to run as an Xcode build phase and assumes that the proper environment
# is set (via xcodebuild)
set -e

SCRIPT_DIR=$(cd $(dirname "$0") ; pwd -P )

# The python script will handle invalid command line arguments as appropriate.
# This script just translates the Xcode environment to the expected format.
case "$TARGETED_DEVICE_FAMILY" in
  "1")
    DEVICES="-d iphone";;
  "2")
    DEVICES="-d ipad";;
  "1,2")
    DEVICES="-d iphone -d ipad";;
  "4")
    DEVICES="-d watch";;
  "1,4")
    DEVICES="-d iphone -d watch";;
  *)
    DEVICES=""
esac

"$SCRIPT_DIR/compile_asset_catalogs.py" -t $IPHONEOS_DEPLOYMENT_TARGET -p $PLATFORM_NAME $DEVICES -o "$TARGET_BUILD_DIR/$FULL_PRODUCT_NAME" $@
