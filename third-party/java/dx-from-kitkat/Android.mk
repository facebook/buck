# Copyright 2006 The Android Open Source Project
#
LOCAL_PATH := $(call my-dir)

# We use copy-file-to-new-target so that the installed
# script files' timestamps are at least as new as the
# .jar files they wrap.

# This tool is prebuilt if we're doing an app-only build.
ifeq ($(TARGET_BUILD_APPS),)

# the dx script
# ============================================================
include $(CLEAR_VARS)
LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := dx

include $(BUILD_SYSTEM)/base_rules.mk

$(LOCAL_BUILT_MODULE): $(HOST_OUT_JAVA_LIBRARIES)/dx$(COMMON_JAVA_PACKAGE_SUFFIX)
$(LOCAL_BUILT_MODULE): $(LOCAL_PATH)/etc/dx | $(ACP)
	@echo "Copy: $(PRIVATE_MODULE) ($@)"
	$(copy-file-to-new-target)
	$(hide) chmod 755 $@

INTERNAL_DALVIK_MODULES += $(LOCAL_INSTALLED_MODULE)

# the mainDexClasses rules
# ============================================================
include $(CLEAR_VARS)
LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := mainDexClasses.rules

include $(BUILD_SYSTEM)/base_rules.mk

$(LOCAL_BUILT_MODULE): $(HOST_OUT_JAVA_LIBRARIES)/dx$(COMMON_JAVA_PACKAGE_SUFFIX)
$(LOCAL_BUILT_MODULE): $(LOCAL_PATH)/etc/mainDexClasses.rules | $(ACP)
	@echo "Copy: $(PRIVATE_MODULE) ($@)"
	$(copy-file-to-new-target)

INTERNAL_DALVIK_MODULES += $(LOCAL_INSTALLED_MODULE)

installed_mainDexClasses.rules := $(LOCAL_INSTALLED_MODULE)

# the shrinkedAndroid jar is a library used by the mainDexClasses script
# ============================================================
include $(CLEAR_VARS)
LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := shrinkedAndroid
LOCAL_BUILT_MODULE_STEM := shrinkedAndroid.jar
LOCAL_MODULE_SUFFIX := $(COMMON_JAVA_PACKAGE_SUFFIX)

include $(BUILD_SYSTEM)/base_rules.mk

$(LOCAL_BUILT_MODULE): PRIVATE_PROGUARD_FLAGS:= \
  -include $(addprefix $(LOCAL_PATH)/, shrinkedAndroid.proguard.flags)
$(LOCAL_BUILT_MODULE): $(call java-lib-files,android_stubs_current) | $(PROGUARD)
	@echo Proguard: $@
	$(hide) $(PROGUARD) -injars "$<(**/*.class)" -outjars $@ $(PRIVATE_PROGUARD_FLAGS)

INTERNAL_DALVIK_MODULES += $(LOCAL_INSTALLED_MODULE)

installed_shrinkedAndroid := $(LOCAL_INSTALLED_MODULE)

# the mainDexClasses script
# ============================================================
include $(CLEAR_VARS)
LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := mainDexClasses

include $(BUILD_SYSTEM)/base_rules.mk

$(LOCAL_BUILT_MODULE): $(HOST_OUT_JAVA_LIBRARIES)/dx$(COMMON_JAVA_PACKAGE_SUFFIX)
$(LOCAL_BUILT_MODULE): $(LOCAL_PATH)/etc/mainDexClasses | $(ACP)
	@echo "Copy: $(PRIVATE_MODULE) ($@)"
	$(copy-file-to-new-target)
	$(hide) chmod 755 $@

$(LOCAL_INSTALLED_MODULE): | $(installed_shrinkedAndroid) $(installed_mainDexClasses.rules)
INTERNAL_DALVIK_MODULES += $(LOCAL_INSTALLED_MODULE)

endif # TARGET_BUILD_APPS

# the dexmerger script
# ============================================================
include $(CLEAR_VARS)
LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := dexmerger

include $(BUILD_SYSTEM)/base_rules.mk

$(LOCAL_BUILT_MODULE): $(HOST_OUT_JAVA_LIBRARIES)/dx$(COMMON_JAVA_PACKAGE_SUFFIX)
$(LOCAL_BUILT_MODULE): $(LOCAL_PATH)/etc/dexmerger | $(ACP)
	@echo "Copy: $(PRIVATE_MODULE) ($@)"
	$(copy-file-to-new-target)
	$(hide) chmod 755 $@

INTERNAL_DALVIK_MODULES += $(LOCAL_INSTALLED_MODULE)

# the jasmin script
# ============================================================
include $(CLEAR_VARS)
LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := jasmin

include $(BUILD_SYSTEM)/base_rules.mk

$(LOCAL_BUILT_MODULE): $(HOST_OUT_JAVA_LIBRARIES)/jasmin.jar
$(LOCAL_BUILT_MODULE): $(LOCAL_PATH)/etc/jasmin | $(ACP)
	@echo "Copy: $(PRIVATE_MODULE) ($@)"
	$(copy-file-to-new-target)
	$(hide) chmod 755 $@

INTERNAL_DALVIK_MODULES += $(LOCAL_INSTALLED_MODULE)

# the jasmin lib
# ============================================================
include $(CLEAR_VARS)
LOCAL_IS_HOST_MODULE := true
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := jasmin.jar

include $(BUILD_SYSTEM)/base_rules.mk

$(LOCAL_BUILT_MODULE): $(LOCAL_PATH)/etc/jasmin.jar | $(ACP)
	@echo "Copy: $(PRIVATE_MODULE) ($@)"
	$(copy-file-to-target)
	$(hide) chmod 644 $@

INTERNAL_DALVIK_MODULES += $(LOCAL_INSTALLED_MODULE)

# the other stuff
# ============================================================
subdirs := $(addprefix $(LOCAL_PATH)/,$(addsuffix /Android.mk, \
		junit-tests \
		src \
	))

include $(subdirs)
