#!/bin/bash
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
