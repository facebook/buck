#!/bin/bash
#
# This generates the static content for Buck's documentation.
# Usage:
#
#     ./docs/soy2html.sh <output-dir>
#
# Caller must be sure that soyweb-prod.sh is already running.
#
# After running this script, you may want to run the following to
# share a preview of the docs internally:
#
#    scp -r <output-dir>/* <hostname>:$/home/${USER}/public_html/buck
#
# Or if you do this frequently, you might want to use rsync instead of scp:
#
#    rsync -az --delete <output-dir>/* <hostname>:/home/${USER}/public_html/buck

set -e

# Always run this script from the root of the Buck project directory.
cd "$(git rev-parse --show-toplevel)"

# The output directory should be the one and only argument.
OUTPUT_DIR="$1"

# Run soy2html.py, taking care to unset proxy env variables as the script will
# use curl internally.
(
  cd docs
  HTTP_PROXY="" HTTPS_PROXY="" http_proxy="" https_proxy="" python soy2html.py "$OUTPUT_DIR"
)

# Generate javadoc and include it in the output directory.
ant javadoc-with-android
mkdir -p "${OUTPUT_DIR}"/javadoc/
cp -r ant-out/javadoc-with-android/* "${OUTPUT_DIR}"/javadoc/
