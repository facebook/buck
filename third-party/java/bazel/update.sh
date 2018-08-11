#!/bin/bash -e -x

readonly WORK_DIR=$(mktemp -d)
readonly BAZEL_DIR="${WORK_DIR}/bazel"
readonly MY_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null && pwd )"
readonly JARJAR_DIR="${MY_DIR}/../jarjar"
readonly JARJAR_PATH="${JARJAR_DIR}/jarjar-1.4.1.jar"

git -C "${WORK_DIR}" clone --depth 1 https://github.com/bazelbuild/bazel.git
pushd "${BAZEL_DIR}"
git apply "${MY_DIR}/skylark-lang.patch"
bazel build //src/main/java/com/google/devtools/build/lib:skylark-lang_deploy.jar \
  //src/main/java/com/google/devtools/build/lib:skylark-lang_deploy-src.jar
java -jar "${JARJAR_PATH}" process "${MY_DIR}/jarjar-rules.txt" \
  bazel-bin/src/main/java/com/google/devtools/build/lib/skylark-lang_deploy.jar \
  "${MY_DIR}/skylark-lang_deploy.jar"
java -jar "${JARJAR_PATH}" process "${MY_DIR}/jarjar-rules.txt" \
  bazel-bin/src/main/java/com/google/devtools/build/lib/skylark-lang_deploy-src.jar \
  "${MY_DIR}/skylark-lang_deploy-src.jar"

readonly commit_hash=$(git rev-parse HEAD)
echo "${commit_hash}" > "${MY_DIR}/COMMIT_HASH.facebook"
popd
