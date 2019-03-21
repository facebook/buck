#!/bin/bash -ex

readonly GIT_COMMIT_HASH=${1-master}
readonly WORK_DIR=$(mktemp -d)
readonly BAZEL_DIR="${WORK_DIR}/bazel"
readonly MY_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"
readonly JARJAR_DIR="${MY_DIR}/../jarjar"
readonly JARJAR_PATH="${JARJAR_DIR}/jarjar-1.4.1.jar"
readonly FINAL_JAR_NAME="bazel"
# Used to speed up clones from github if a local clone is available
readonly BAZEL_LOCAL=${BAZEL_LOCAL-}
readonly NO_CLEANUP=${NO_CLEANUP-}

if [ -z "$NO_CLEANUP" ] && [ -n "${WORK_DIR}" ]; then
  trap "echo Cleaning up ${WORK_DIR}; rm -rf ${WORK_DIR}" EXIT
fi

if [ -z "$BAZEL_LOCAL" ]; then
  git -C "${WORK_DIR}" clone git@github.com:bazelbuild/bazel.git
else
  mkdir -p ${BAZEL_DIR}
  rsync --progress -a "${BAZEL_LOCAL}/" "${BAZEL_DIR}/"
fi
pushd "${BAZEL_DIR}"

git checkout $GIT_COMMIT_HASH

remove_jar_entries() {
  zip -d $1 com/google/common/\*
  zip -d $1 com/google/gson/\*
  zip -d $1 org/objectweb/asm/\*
  zip -d $1 javax/annotation/\*
  zip -d $1 com/google/errorprone/\*
  zip -d $1 com/google/protobuf/\*
}

git apply "${MY_DIR}/bazel.patch"
bazel build //src/main/java/com/google/devtools/build/lib:bazel-jar_deploy.jar \
  //src/main/java/com/google/devtools/build/lib:bazel-jar_deploy-src.jar

cp bazel-bin/src/main/java/com/google/devtools/build/lib/bazel-jar_deploy.jar \
  "${MY_DIR}/${FINAL_JAR_NAME}_deploy.jar"
remove_jar_entries "${MY_DIR}/${FINAL_JAR_NAME}_deploy.jar"

cp bazel-bin/src/main/java/com/google/devtools/build/lib/bazel-jar_deploy-src.jar \
  "${MY_DIR}/${FINAL_JAR_NAME}_deploy-src.jar"

readonly commit_hash=$(git rev-parse HEAD)
echo "${commit_hash}" > "${MY_DIR}/COMMIT_HASH.facebook"
popd
