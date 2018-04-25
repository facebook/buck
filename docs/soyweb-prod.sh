#!/bin/bash

# Always run this script from the root of the Buck project directory.

#
# Remove any residual files that could derail build and publication.
#
cd "$(git rev-parse --show-toplevel)" || exit
ant clean

cd "$(git rev-parse --show-toplevel)/docs" || exit
exec java -jar plovr-81ed862.jar soyweb --port 9814 --dir . --globals globals.json

