#!/bin/bash

OUT=$1
TMP=$2
MANIFEST=$3
PRIMARY=$4
DEP=$5

cp $MANIFEST $TMP
cp $PRIMARY $TMP/classes.jar
mkdir $TMP/libs
cp $DEP $TMP/libs
mkdir -p $TMP/res/values
echo '<?xml version="1.0" encoding="utf-8" ?>
<resources>
  <string name="app_name">Sample App</string>
  <string name="base_button">Hello world!</string>
</resources>' > $TMP/res/values/strings.xml
jar cf $OUT -C $TMP .
