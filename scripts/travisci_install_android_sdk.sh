#!/bin/bash
set -x

export ANDROID_TOOL=${ANDROID_HOME}/tools/android

# Because we cache ANDROID_HOME in Travis, we cannot test for the existence of
# the directory; it always gets created before we run.  Instead, check for the
# tool we care about, and if it doesn't exist, download the SDK.
if [ ! -x ${ANDROID_TOOL} ]; then
  wget http://dl.google.com/android/android-sdk_r23-linux.tgz
  tar -zxf android-sdk_r23-linux.tgz
  rm android-sdk_r23-linux.tgz
  rm -rf ${ANDROID_HOME}
  mv android-sdk-linux ${ANDROID_HOME}
fi

# We always update the SDK, even if it is cached, in case we change the
# versions of things we care about.
# Values from `android list sdk --extended --all`
(while :
do
    echo y
    sleep 2
 done) | ${ANDROID_TOOL} update sdk \
  --force \
  --no-ui \
  --all \
  --filter \
tools,\
platform-tools,\
build-tools-23.0.2,\
android-23,\
addon-google_apis-google-23,\
android-21,\
addon-google_apis-google-21,\
extra-android-support
