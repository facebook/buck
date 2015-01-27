#!/bin/bash

set -e

TMP=$1
OUT=$2

cd $TMP

# Write out AndroidManifest.xml.
echo "\
<manifest xmlns:android='http://schemas.android.com/apk/res/android' package='com.example' />
" > AndroidManifest.xml

# Write out a resource.
mkdir -p res/values
echo "\
<?xml version='1.0' encoding='utf-8' ?>
<resources>
  <string name='app_name'>Hello World</string>
</resources>
" > res/values/strings.xml

# Include some .class files in classes.jar because the .aar spec requires it.
echo "\
package com.example;

public class Utils {
  public static String capitalize(String str) {
    return str.substring(0, 1).toUpperCase() + str.substring(1);
  }
}
" > Utils.java
mkdir classes
javac -source 1.7 -target 1.7 -d classes Utils.java
jar -cf classes.jar -C classes .
rm -rf classes Utils.java

# Note that we do not include an R.txt file, even though it is required by the .aar spec.
# Currently, Buck does not check for its existence.

zip -r $OUT .
