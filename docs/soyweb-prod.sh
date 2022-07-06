#!/bin/bash
# Copyright (c) Meta Platforms, Inc. and affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


# Always run this script from the root of the Buck project directory.

#
# Remove any residual files that could derail build and publication.
#

show_help() {
  cat <<-EOF
Usage: soyweb-prod.sh [--buck-location BUCK_LOCATION]
  --buck-location Sets the location of the Buck executable to use
  --help          Show this help
EOF
  exit 1
}

BUCK_LOCATION="buck"
for arg do
  shift
  case $arg in
    --buck-location)
      BUCK_LOCATION="$1"
      shift
      ;;
    --help) show_help ;;
    *) set -- "$@" "$arg" ;;
  esac
done

DOCS_DIR=$(dirname "$0")
BUCK_DIR=$(realpath "$DOCS_DIR/..")
DOCS_DIR=$(realpath "$DOCS_DIR")

cd "$BUCK_DIR" || exit
ant clean

cd "$BUCK_DIR/docs" || exit
"${BUCK_LOCATION}" run //docs:generate_buckconfig_aliases
exec java -jar plovr-81ed862.jar soyweb --port 9814 --dir . --globals globals.json

