#!/bin/bash
# Copyright 2018-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.
#
# This generates the documentation for Buck and publishes it to GitHub.
# Usage:
#
#   ./docs/publish.sh
#
# Caller must be sure they have the appropriate credentials configured
# to push to the GitHub repo.


set -e

start_soyweb() {
  echo "Starting soyweb-prod.sh" >&2
  docs/soyweb-prod.sh >&2 &
  local soyweb_pid=$!
  sleep 2
  if ! kill -0 "$soyweb_pid" >/dev/null 2>&1; then
    echo "Soyweb not running after 2 seconds. kill 0 on pid ${soyweb_pid} failed" >&2
    exit 1
  fi
  echo "Started soyweb-prod.sh as pid ${soyweb_pid}" >&2
  echo "$soyweb_pid"
}

show_help() {
  cat <<-EOF
Usage: publish.sh [--start-soyweb] [--keep-files]
  --start-soyweb Start soyweb and shut it down when the script is finished
  --keep-files   Keep temporary files after attempting to publish
EOF
  exit 1
}

START_SOYWEB=0
KEEP_FILES=0
SOYWEB_PID=0
for arg do
  shift
  case $arg in
    --start-soyweb) START_SOYWEB=1 ;;
    --keep-files) KEEP_FILES=1 ;;
    --help) show_help ;;
    *) set -- "$@" "$arg" ;;
  esac
done

STATIC_FILES_DIR=$(mktemp -d)

# Always run this script from the root of the Buck project directory.
cd $(git rev-parse --show-toplevel)

if [ $START_SOYWEB -eq 1 ]; then
  SOYWEB_PID=$(($(start_soyweb) + 0))
fi

# Make sure we cleanup
trap_command=""
if [ $SOYWEB_PID -gt 0 ]; then
  trap_command="echo 'Stopping soyweb (pid ${SOYWEB_PID})'; kill -9 ${SOYWEB_PID};"
fi
if [ $KEEP_FILES -eq 0 ] && [ -n "$STATIC_FILES_DIR" ]; then
  trap_command="${trap_command}echo 'Removing temp dir at ${STATIC_FILES_DIR}'; rm -rf ${STATIC_FILES_DIR};"
fi
if [ -n "$trap_command" ]; then
  trap "$trap_command" EXIT
fi

echo "Documentation working directory is ${STATIC_FILES_DIR}"

# Create a clean checkout of the gh-pages branch with no data:
if [ -z "$1" ]
then
  git clone git@github.com:facebook/buck.git $STATIC_FILES_DIR
else
  cp -r "$1" $STATIC_FILES_DIR
fi
cd $STATIC_FILES_DIR

# May need to do this if you are creating gh-pages for the first time.
git checkout master

git checkout --orphan gh-pages
git rm -rf .
cd -

# Generate the docs in the repo:
./docs/soy2html.sh $STATIC_FILES_DIR

# Commit the new version of the docs:
cd $STATIC_FILES_DIR
git add .
git commit -m "Updated HTML documentation."

# Push the new version of the docs to GitHub:
set +e
git push origin gh-pages --force
EXIT_CODE=$?
set -e

# Unfortunately, this script is not bulletproof,
# so inform the user of the failure.
if [ $EXIT_CODE -ne 0 ]; then
  echo "WARNING: 'git push origin gh-pages failed'. "
  echo "Try going to https://github.com/facebook/buck/branches"
  echo "and re-running this script."
fi
