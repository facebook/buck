# Copyright 2011 The Android Open Source Project

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_JAVA_LIBRARIES := dx junit
LOCAL_MODULE_TAGS := tests
LOCAL_MODULE:= dx-tests
include $(BUILD_HOST_JAVA_LIBRARY)
