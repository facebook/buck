LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := one
LOCAL_SRC_FILES := one.c

include $(BUILD_SHARED_LIBRARY)
