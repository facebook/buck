LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= mybinary.c

LOCAL_CFLAGS := -Wall -Werror

LOCAL_MODULE := mybinary-disguised-exe

include $(BUILD_EXECUTABLE)
