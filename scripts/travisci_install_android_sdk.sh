#!/bin/bash
set -x

export SDK_FILENAME="android-sdk_r23-linux.tgz"
export CACHED_PATH="${HOME}/sdk_cache/$SDK_FILENAME"

if [ ! -x "$CACHED_PATH" ]; then
  echo "Downloading SDK."
  wget "https://dl.google.com/android/$SDK_FILENAME"
else
  echo "Using cached SDK."
  mv "$CACHED_PATH" .
fi

# Unzip the sdk.
tar -zxf "$SDK_FILENAME"
rm "$SDK_FILENAME"

# Move it into our expected place.
rm -rf ${ANDROID_HOME}
mv android-sdk-linux ${ANDROID_HOME}

export ANDROID_TOOL=${ANDROID_HOME}/tools/android

function android_update_sdk() {
  (
    set +x
    while true
    do
      echo y
      sleep 2
    done
  ) | ${ANDROID_TOOL} update sdk \
    --force \
    --no-ui \
    --all \
    "$@"
}

# We always update the SDK, even if it is cached, in case we change the
# versions of things we care about.
# Values from `android list sdk --extended --all`

# We install the SDK in multiple invocations because it seems that the list
# of available packages depends on which other packages are already installed
# and installing all packages in a single invocation does not work.
android_update_sdk --filter platform-tools
android_update_sdk --filter tools
android_update_sdk --filter \
build-tools-23.0.2,\
android-23,\
addon-google_apis-google-23,\
android-21,\
addon-google_apis-google-21,\
extra-android-support
