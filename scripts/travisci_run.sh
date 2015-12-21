#!/bin/sh
set -eux

# We export TERM=dumb to hide Buck's SuperConsole
# because otherwise Travis thinks our logs are too long.
export TERM=dumb

# Use Buck to run the tests first because its output is more
# conducive to diagnosing failed tests than Ant's is.
./bin/buck build buck || { cat buck-out/log/ant.log; exit 1; }
# There are only two cores in the containers, but Buck thinks there are 12
# and tries to use all of them.  As a result, we seem to have our test
# processes killed if we do not limit our threads.
# Until we get https://github.com/facebook/buck/pull/479, we cannot run the
# Go tests in Travis
./bin/buck test \
  --num-threads=3

# Run all the other checks with ant.
ant travis
