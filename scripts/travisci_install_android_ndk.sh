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

set -x

export NDK_VERSION_STRING="android-ndk-r10e"
export NDK_FILENAME="$NDK_VERSION_STRING-linux-x86_64.bin"

export CACHED_PATH="${HOME}/ndk_cache/$NDK_FILENAME"

if [ ! -f "$CACHED_PATH" ]; then
  echo "Downloading NDK."
  wget "https://dl.google.com/android/ndk/$NDK_FILENAME"
else
  echo "Using cached NDK."
  mv "$CACHED_PATH" .
fi

# Uncpack the ndk.
chmod a+x "$NDK_FILENAME"
# Suppress the output to not spam the logs.
"./$NDK_FILENAME" > /dev/null
rm "$NDK_FILENAME";

# Move ndk into the proper place.
rm -rf ${NDK_HOME}
mv android-ndk-r10e ${NDK_HOME}
