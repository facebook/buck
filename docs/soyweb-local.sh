#!/bin/bash

# Always run this script from the root of the Buck project directory.
cd $(git rev-parse --show-toplevel)

cd docs

# Unfortunately, plovr has a bug where we cannot override values in globals.json
# from the command line. Instead, we create a copy of globals.json with the
# modifications we need to run soyweb locally and use that. 
TMP_DIR=`mktemp -d -t new_module_test.XXXXXXXX`
trap "rm -rf $TMP_DIR" EXIT HUP INT TERM
sed -e 's#/buck/#/#' globals.json > $TMP_DIR/globals.json 

java -jar plovr-81ed862.jar soyweb --dir . --globals $TMP_DIR/globals.json
