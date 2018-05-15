#!/bin/bash
# In remote execution, a binary is run at the root directory. For Buck, this root directory may not
# be the same as the build root (the root directory is the common ancestor of all the present
# cells).
# This trampoline is used to run the buck process in the correct subdirectory.
# It also absolutizes the buck classpath and the bootstrap classpath.

# Run with -e so the script will fail if any of the steps fail.
set -e

(
function resolve() {
  echo $1 | tr ':' '\n' | sed "s/^\([^/].*\)$/$(echo $PWD | sed 's/\//\\\//g')\/\1/" | tr '\n' ':'
}

export BUCK_CLASSPATH=$(resolve $BUCK_CLASSPATH)
export CLASSPATH=$(resolve $CLASSPATH)
export BUCK_ISOLATED_ROOT=$PWD

cd $1
java -cp $CLASSPATH com.facebook.buck.cli.bootstrapper.ClassLoaderBootstrapper com.facebook.buck.rules.modern.builders.OutOfProcessIsolatedBuilder $BUCK_ISOLATED_ROOT $1 $2
)

