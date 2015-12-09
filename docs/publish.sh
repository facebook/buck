#!/bin/bash
#
# This generates the documentation for Buck and publishes it to GitHub.
# Usage:
#
#   ./docs/publish.sh
#
# Caller must be sure that soyweb-prod.sh is already running and that caller
# has the appropriate credentials configured to push to the GitHub repo.

set -e

# Always run this script from the root of the Buck project directory.
cd $(git rev-parse --show-toplevel)

STATIC_FILES_DIR=/tmp/buck-public-documentation/

# Create a clean checkout of the gh-pages branch with no data:
rm -rf $STATIC_FILES_DIR
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
