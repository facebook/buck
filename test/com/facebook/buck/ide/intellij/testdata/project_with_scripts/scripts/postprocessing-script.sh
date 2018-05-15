#!/bin/sh

THIS_FILE="$(basename "${BASH_SOURCE[0]}")"

echo "$THIS_FILE says: $BUCK_PROJECT_TARGETS" > "$PWD/postprocessing-script-finished.txt"
