#!/bin/bash
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
