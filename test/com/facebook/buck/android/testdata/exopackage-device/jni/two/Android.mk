LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := two
LOCAL_SRC_FILES := two.c

include $(BUILD_SHARED_LIBRARY)
