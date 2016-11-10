#!/bin/sh

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

echo $BUCK_PROJECT_TARGETS > $DIR/preprocessing-script-finished.txt
