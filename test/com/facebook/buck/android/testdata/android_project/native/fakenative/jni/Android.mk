LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= fakesystem.c

LOCAL_CFLAGS := -Wall -Werror

LOCAL_MODULE := fakenative

include $(BUILD_SHARED_LIBRARY)
