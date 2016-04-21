#!/bin/bash
set -x

# Because we cache NDK_HOME in Travis, we cannot test for the existence of the
# directory; it always gets created before we run.  Instead, check for the
# release text file, and if it doesn't exist, download the NDK.
if [ ! -f ${NDK_HOME}/RELEASE.TXT ]; then
  wget https://dl.google.com/android/ndk/android-ndk-r10e-linux-x86_64.bin
  chmod a+x android-ndk-r10e-linux-x86_64.bin
  # Suppress the output to not spam the logs.
  ./android-ndk-r10e-linux-x86_64.bin > /dev/null
  rm android-ndk-r10e-linux-x86_64.bin
  rm -rf ${NDK_HOME}
  mv android-ndk-r10e ${NDK_HOME}
fi
