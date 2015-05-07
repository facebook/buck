#!/bin/bash

THIS_DIR=$(dirname "$0")
THIS_DIR=$(pwd)/$THIS_DIR
OUTPUT_FILE="${@: -1}"

case "$1" in
'bundle')
  cat $THIS_DIR/app/sample.android.js $THIS_DIR/app/helpers.js > $OUTPUT_FILE
  exit 0
  ;;
'list-dependencies')
  echo $THIS_DIR/app/sample.android.js > $OUTPUT_FILE
  echo $THIS_DIR/app/helpers.js >> $OUTPUT_FILE
  exit 0
  ;;
*)
  echo "Invalid command"
  exit 1
esac
