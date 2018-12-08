#!/bin/sh

if [! -d $ANDROID_HOME]; then
    wget -q https://dl.google.com/android/repository/sdk-tools-linux-3859397.zip -O /tmp/sdk.zip
    mkdir $ANDROID_HOME
    unzip -q -d $ANDROID_HOME /tmp/sdk.zip
    rm /tmp/sdk.zip
    yes | $ANDROID_HOME/tools/bin/sdkmanager --licenses && $ANDROID_HOME/tools/bin/sdkmanager --update
    $ANDROID_HOME/tools/bin/sdkmanager 'tools' 'platform-tools' 'build-tools;23.0.3' 'platforms;android-23' 'platforms;android-21'  'add-ons;addon-google_apis-google-21' 'add-ons;addon-google_apis-google-23' 'extras;android;m2repository'
fi