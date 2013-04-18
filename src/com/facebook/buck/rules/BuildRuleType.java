/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules;

public enum BuildRuleType {
  ANDROID_BINARY,
  ANDROID_INSTRUMENTATION_APK,
  ANDROID_LIBRARY,
  ANDROID_MANIFEST,
  ANDROID_RESOURCE,
  APK_GENRULE,
  EXPORT_FILE,
  GEN_AIDL,
  GEN_PARCELABLE,
  GENRULE,
  INPUT,
  JAVA_BINARY,
  JAVA_LIBRARY,
  JAVA_TEST,
  NDK_LIBRARY,
  PREBUILT_JAR,
  PREBUILT_NATIVE_LIBRARY,
  PROJECT_CONFIG,
  PYTHON_BINARY,
  PYTHON_LIBRARY,
  ROBOLECTRIC_TEST,
  SH_TEST,
  ;

  private final boolean isTestRule;

  private BuildRuleType() {
    isTestRule = name().endsWith("_TEST");
  }

  /**
   * @return the name as displayed in a build file, such as "java_library"
   */
  public String getDisplayName() {
    return name().toLowerCase();
  }

  public boolean isTestRule() {
    return isTestRule;
  }
}
