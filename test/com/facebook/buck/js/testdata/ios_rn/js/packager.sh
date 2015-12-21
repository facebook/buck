#!/bin/bash

THIS_DIR=$(pwd)/js
LAST_ARG="${@: -1}"
SECOND_LAST_ARG="${@: -3:1}"
THIRD_LAST_ARG="${@: -5:1}"

copy_assets() {
  ASSETS_DIR="$1"
  mkdir -p $ASSETS_DIR
  cp "$THIS_DIR/app/image@1.5x.png" $ASSETS_DIR/
  cp "$THIS_DIR/app/image@2x.png" $ASSETS_DIR/
  cp "$THIS_DIR/app/image@3x.png" $ASSETS_DIR/
}

case "$1" in
'bundle')
  OUTPUT_DIR="$SECOND_LAST_ARG"
  OUTPUT_FILE="$THIRD_LAST_ARG"
  cat $THIS_DIR/app/sample.ios.js $THIS_DIR/app/helpers.js > $OUTPUT_FILE

  copy_assets "$OUTPUT_DIR/assets/Apps/DemoApp/"

  # write something as the source map because the rule caches this output.
  echo "sourcemap" > "$LAST_ARG"

  exit 0
  ;;
'unbundle')
  ASSET_DIR="$SECOND_LAST_ARG"
  APP_ENTRY_FILE="$THIRD_LAST_ARG"
  JS_MODULE_DIR=`dirname "$APP_ENTRY_FILE"`/js

  mkdir "$JS_MODULE_DIR"
  cp $THIS_DIR/app/sample.ios.js "$APP_ENTRY_FILE"
  cp $THIS_DIR/app/helpers.js "$JS_MODULE_DIR/helpers.js"

  copy_assets "$ASSET_DIR/assets/Apps/DemoApp-Unbundle/"

  # write something as the source map because the rule caches this output.
  echo "sourcemap" > "$LAST_ARG"

  exit 0
  ;;
'list-dependencies')
  OUTPUT_FILE="$LAST_ARG"
  echo $THIS_DIR/app/sample.ios.js > $OUTPUT_FILE
  echo $THIS_DIR/app/helpers.js >> $OUTPUT_FILE
  echo $THIS_DIR/app/image@1.5x.png >> $OUTPUT_FILE
  echo $THIS_DIR/app/image@2x.png >> $OUTPUT_FILE
  echo $THIS_DIR/app/image@3x.png >> $OUTPUT_FILE
  exit 0
  ;;
*)
  echo "Invalid command"
  exit 1
esac
