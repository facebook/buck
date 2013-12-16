#!/bin/bash

# Generates a JAR of .java files for the .jj files.
# Based on https://github.com/bolinfest/plovr/blob/master/closure/closure-templates/gen_parser.sh

JAVACC="$1"
OUTPUT="$2"
TMP="$3"
shift 3

cd "$TMP" && java -classpath "$JAVACC" org.javacc.parser.Main "$@" && zip -r "$OUTPUT" *.java
