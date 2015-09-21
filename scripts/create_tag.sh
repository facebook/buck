#!/bin/bash -e

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
