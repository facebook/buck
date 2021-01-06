# Copyright (c) Facebook, Inc. and its affiliates.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
  agent.c \
  miniz.c \
  miniz-extensions.c \
  get-signature.c \
  mkdir-p.c \
  receive-file.c \

MINIZ_CPPFLAGS := \
	-DMINIZ_NO_COMPRESSOR \
	-DMINIZ_NO_ARCHIVE_WRITING_APIS \
	-DMINIZ_NO_TIME \
	-DMINIZ_NO_ZLIB_COMPATIBLE_NAMES \

LOCAL_CPPFLAGS := $(MINIZ_CPPFLAGS)
LOCAL_CFLAGS := -Wall -Werror -std=gnu99 -fno-strict-aliasing

LOCAL_MODULE := agent-disguised-exe

include $(BUILD_EXECUTABLE)
