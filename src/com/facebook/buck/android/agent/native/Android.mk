LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
  agent.c \
  miniz.c \
  miniz-extensions.c \
  get-signature.c \
  receive-file.c \

MINIZ_CPPFLAGS := \
	-DMINIZ_NO_COMPRESSOR \
	-DMINIZ_NO_ARCHIVE_WRITING_APIS \
	-DMINIZ_NO_TIME \
	-DMINIZ_NO_ZLIB_COMPATIBLE_NAMES \

LOCAL_CPPFLAGS := $(MINIZ_CPPFLAGS)
LOCAL_CFLAGS := -Wall -Werror -std=gnu99

LOCAL_MODULE := agent-disguised-exe

include $(BUILD_EXECUTABLE)
