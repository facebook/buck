#!/bin/bash
# Copyright 2019-present Facebook, Inc.
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


# Compile Protocol Buffers definition files, remove redundant intermediary files and tag generated
# files.

# Setup - exit on any failure, record current dir and go to repo root.
set -e
current_dir=$(pwd)

function ls_files_cmd {
    pattern="$1"
    find . -path "./${pattern}"
}

# Add generated tag to make tooling correctly recognize them as generated.
# Insert tag name into the command so that tooling does not mistaken this file with generated code.
function set_generated_tag {
    tag_name="generated"
    pattern="$1"
    for f in $(ls_files_cmd "${pattern}"); do
      # Do not use sed - the only portable version of prepending a line is awful.
      temp_file=$(mktemp)
      echo "// @$tag_name" > "$temp_file"
      cat "$f" >> "$temp_file"
      mv "$temp_file" "$f"
    done
}

function remove_files {
    pattern="$1"
    for f in $(ls_files_cmd "${pattern}"); do
       rm "$f"
    done
}

script_dir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
# assume this script is in third-party/java/protobuf
cd "${script_dir}/../../.."

#----------------------------------------------
#  Sync protoc version with library version used.
#----------------------------------------------
#   PROTOC_VERSION=3.7.0
#   PROTOC_ZIP=protoc-${PROTOC_VERSION}-osx-x86_64.zip
#   curl -OL "https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOC_VERSION}/$PROTOC_ZIP"
#   sudo unzip -o $PROTOC_ZIP -d /usr/local bin/protoc
#   sudo unzip -o $PROTOC_ZIP -d /usr/local 'include/*'
#   rm -f $PROTOC_ZIP
#   sudo chown -R $(whoami) /usr/local/bin/protoc

# default for macos
OS="osx"
PROTOC="protoc"


# Compile all proto files.
remove_files src-gen/**/proto/*.java
remove_files src-gen/**/model/*.java
for f in $(ls_files_cmd src/**/*.proto); do
   $PROTOC \
   --java_out=src-gen/ \
   --java_opt=annotate_code \
   --plugin=protoc-gen-grpc-java=third-party/java/grpc/protoc-gen-grpc-java-1.10.1-${OS}-x86_64.exe \
   --grpc-java_out=src-gen/ \
   "$f"
done

# Remove metadata files.
remove_files src-gen/**/*.pb.meta

set_generated_tag src-gen/**/proto/*.java
set_generated_tag src-gen/**/model/*.java

#Verify generated srcs are valid
buck build buck//src-gen/...

cd "$current_dir"
set +e
