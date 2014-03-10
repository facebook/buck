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

import com.google.common.base.Preconditions;

public final class BuildRuleType {
  // TODO(simons): Move each of these closer to the BuildRules they represent.
  public static final BuildRuleType ANDROID_BINARY = new BuildRuleType("android_binary");
  public static final BuildRuleType ANDROID_INSTRUMENTATION_APK =
      new BuildRuleType("android_instrumentation_apk");
  public static final BuildRuleType ANDROID_LIBRARY = new BuildRuleType("android_library");
  public static final BuildRuleType ANDROID_MANIFEST = new BuildRuleType("android_manifest");
  public static final BuildRuleType ANDROID_RESOURCE = new BuildRuleType("android_resource");
  public static final BuildRuleType APK_GENRULE = new BuildRuleType("apk_genrule");
  public static final BuildRuleType GEN_PARCELABLE = new BuildRuleType("gen_parcelable");
  public static final BuildRuleType GENRULE = new BuildRuleType("genrule");
  public static final BuildRuleType JAVA_BINARY = new BuildRuleType("java_binary");
  public static final BuildRuleType JAVA_LIBRARY = new BuildRuleType("java_library");
  public static final BuildRuleType JAVA_TEST = new BuildRuleType("java_test");
  public static final BuildRuleType KEYSTORE = new BuildRuleType("keystore");
  public static final BuildRuleType NDK_LIBRARY = new BuildRuleType("ndk_library");
  public static final BuildRuleType PREBUILT_JAR = new BuildRuleType("prebuilt_jar");
  public static final BuildRuleType PREBUILT_NATIVE_LIBRARY =
      new BuildRuleType("prebuilt_native_library");
  public static final BuildRuleType PROJECT_CONFIG = new BuildRuleType("project_config");
  public static final BuildRuleType PYTHON_BINARY = new BuildRuleType("python_binary");
  public static final BuildRuleType ROBOLECTRIC_TEST = new BuildRuleType("robolectric_test");
  public static final BuildRuleType SH_BINARY = new BuildRuleType("sh_binary");
  public static final BuildRuleType SH_TEST = new BuildRuleType("sh_test");

  // Internal rule types. Denoted by leading trailing underscore.
  public static final BuildRuleType AAPT_PACKAGE = new BuildRuleType("_aapt_package");
  public static final BuildRuleType DUMMY_R_DOT_JAVA = new BuildRuleType("_dummy_r_dot_java");
  public static final BuildRuleType RESOURCES_FILTER = new BuildRuleType("_resources_filter");
  public static final BuildRuleType PRE_DEX = new BuildRuleType("_pre_dex");
  public static final BuildRuleType DEX_MERGE = new BuildRuleType("_dex_merge");
  public static final BuildRuleType UBER_R_DOT_JAVA = new BuildRuleType("_uber_r_dot_java");

  private final String name;
  private final boolean isTestRule;

  /**
   * @param name must match the name of a type of build rule used in a build file (eg. "genrule").
   */
  public BuildRuleType(String name)  {
    this.name = Preconditions.checkNotNull(name).toLowerCase();
    this.isTestRule = name.endsWith("_test");
  }

  /**
   * @return the name as displayed in a build file, such as "java_library"
   */
  public String getName() {
    return name;
  }

  public boolean isTestRule() {
    return isTestRule;
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null || !(that instanceof BuildRuleType)) {
      return false;
    }
    return getName().equals(((BuildRuleType) that).getName());
  }

  @Override
  public String toString() {
    return name;
  }
}
