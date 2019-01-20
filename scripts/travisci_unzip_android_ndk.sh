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
set -e

if [ -z "$1" ]; then
  echo "Must specify Android NDK version"
  exit 1
fi

export NDK_VERSION_STRING="$1"
export NDK_FILENAME="$NDK_VERSION_STRING-linux-x86_64.zip"

export CACHED_PATH="${HOME}/ndk_cache/$NDK_FILENAME"

if [ ! -f "$CACHED_PATH" ]; then
  echo "Downloading NDK."
  wget "https://dl.google.com/android/repository/$NDK_FILENAME"
else
  echo "Using cached NDK."
  mv "$CACHED_PATH" .
fi

unzip -o "./$NDK_FILENAME" > /dev/null
rm "$NDK_FILENAME"

# Move ndk into the proper place.
rm -rf "${NDK_HOME}"
mv "${NDK_VERSION_STRING}" "${NDK_HOME}"
