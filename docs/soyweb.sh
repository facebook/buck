#!/bin/bash

dir="$(dirname "$0")"
cd "$dir"

#
# If you're on OS X, you probably want the empty ROOT setting!
#
if [ "`uname -s`" = "Darwin" ]
then
  ROOT='/'
else
  ROOT='/buck/'
fi

#
# Build the globals JSON file
#
sed -e "s,__ROOT__,$ROOT," <globals.json.in >.globals.json.out

java -jar plovr-81ed862.jar soyweb --dir . --globals .globals.json.out
