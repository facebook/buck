#!/bin/bash

set -e

# grep the src/ directory for .java files that contain the string 'new ProjectFilesystem('
# as a proxy for the number of places a ProjectFilesystem is created in Buck.
#
# There should only be a tightly constrained number of instances.
#
# At the time of this writing, this occurs in src/com/facebook/buck/cli/Main.java, which is
# sensible because a ProjectFilesystem should be created at the start of a Buck process and
# then injected throughout. It also occurs when we try and create new Repository instances.
NUM=`find src -name '*.java' | xargs grep ' = new ProjectFilesystem(' | wc -l`

# Remove leading and trailing whitespace.
NUM=$(echo $NUM)

# Once in Main, once in Repository
if [ "$NUM" = "2" ]; then
  exit 0
else
  echo "There should be only two places where a ProjectFilesystem is created but there were $NUM."
  exit 1
fi
