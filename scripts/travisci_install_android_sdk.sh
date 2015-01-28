#!/bin/bash
set -x

# Values from `android list sdk --extended --all`
(while :
do
    echo y
    sleep 2
 done) | android update sdk --force --no-ui --all --filter \
tools,\
platform-tools,\
build-tools-19.1.0,\
android-16,\
addon-google_apis-google-16,\
android-18,\
addon-google_apis-google-18,\
android-19,\
addon-google_apis-google-19,\
android-21,\
addon-google_apis-google-21,\
extra-android-support
