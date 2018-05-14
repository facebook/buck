skylark-lang_deploy.jar was built using following steps:
1) checkout 5c3f5c9be7fa40d4fb3c35756891fab8483ca406 revision of bazel
2) git apply skylark-lang.patch
3) bazel build //src/main/java/com/google/devtools/build/lib:skylark-lang_deploy.jar
4) bazel build //src/main/java/com/google/devtools/build/lib:skylark-lang_deploy-src.jar
5) java -jar jarjar.jar process jarjar-rules.txt bazel-bin/src/main/java/com/google/devtools/build/lib/skylark-lang_deploy.jar third-party/java/bazel/skylark-lang_deploy.jar
6) java -jar jarjar.jar process jarjar-rules.txt bazel-bin/src/main/java/com/google/devtools/build/lib/skylark-lang_deploy-src.jar third-party/java/bazel/skylark-lang_deploy-src.jar

NOTE: this is not an official public API and is a subject to change, so above procedure might
have to be modified if skylark API changes.
