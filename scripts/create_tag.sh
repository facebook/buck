#!/bin/bash -e
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


DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )
BUCKRELEASE_FILE="$DIR"/../.buckrelease
DEFAULT_TAG=`date +v%Y.%m.%d.01`

shopt -s nocasematch

read -e -p "Enter tag name to create [hit enter for $DEFAULT_TAG]: " tag

if [[ -z $tag ]]; then
    tag="$DEFAULT_TAG"
fi

echo -n "Creating and committing .buckrelease file for $tag... "

echo "$tag" > "$BUCKRELEASE_FILE"
git add "$BUCKRELEASE_FILE"
git commit --allow-empty -q -m "Prepare release $tag."
echo "done."

git tag -f "$tag"

read -e -p "Run 'git push origin $tag'? [Y/n] " ok
if [[ $ok =~ ^(yes|y)$ ]]; then
    git push origin "$tag"
fi
