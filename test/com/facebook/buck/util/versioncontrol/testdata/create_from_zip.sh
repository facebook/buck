#!/bin/bash

set -e

# Dump from hg_repos.zip -> hg bundle + bookmarks list for integration tests

dump() {
  pushd "$1"
  hg bundle -a ../"$1".tar.bz2
  hg book --all  | sed -e 's,\*,,' -e 's,[0-9]:,,'  | awk '{print $1" "$2}' >../"$1".bookmarks
  popd
}

zip_file="${PWD}/hg_repos.zip"
temp_dir=$(mktemp -d)
trap 'if [ -z "$temp_dir" ] && [ -d "$temp_dir" ]; then rm -rf "$temp_dir"; fi' EXIT
cp "$zip_file" "$temp_dir/hg_repos.zip"
pushd "$temp_dir"
unzip hg_repos.zip
dump hg_repo_two
dump hg_repo_three
popd
cp "$temp_dir/"*.{tar.bz2,bookmarks} ./
