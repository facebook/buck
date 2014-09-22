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
  // Internal rule types. Denoted by leading trailing underscore.
  public static final BuildRuleType AAPT_PACKAGE = new BuildRuleType("_aapt_package");
  public static final BuildRuleType EXOPACKAGE_DEPS_ABI = new BuildRuleType("_exopackage_deps_abi");
  public static final BuildRuleType DUMMY_R_DOT_JAVA = new BuildRuleType("_dummy_r_dot_java");
  public static final BuildRuleType GWT_MODULE = new BuildRuleType("_gwt_module");
  public static final BuildRuleType RESOURCES_FILTER = new BuildRuleType("_resources_filter");
  public static final BuildRuleType PRE_DEX = new BuildRuleType("_pre_dex");
  public static final BuildRuleType DEX_MERGE = new BuildRuleType("_dex_merge");
  public static final BuildRuleType PACKAGE_STRING_ASSETS =
      new BuildRuleType("_package_string_assets");

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
