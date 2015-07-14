#!/bin/bash

THIS_DIR=$(dirname "$0")
THIS_DIR=$(pwd)/$THIS_DIR
LAST_ARG="${@: -1}"
SECOND_LAST_ARG="${@: -3:1}"
THIRD_LAST_ARG="${@: -5:1}"

case "$1" in
'bundle')
  OUTPUT_DIR="$SECOND_LAST_ARG"
  OUTPUT_FILE="$THIRD_LAST_ARG"
  cat $THIS_DIR/app/sample.ios.js $THIS_DIR/app/helpers.js > $OUTPUT_FILE

  ASSETS_DIR="$OUTPUT_DIR/assets/Apps/DemoApp/"
  mkdir -p $ASSETS_DIR
  cp "$THIS_DIR/app/image@1.5x.png" $ASSETS_DIR/
  cp "$THIS_DIR/app/image@2x.png" $ASSETS_DIR/
  cp "$THIS_DIR/app/image@3x.png" $ASSETS_DIR/

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
