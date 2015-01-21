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

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
public abstract class BuildRuleType {
  // Internal rule types. Denoted by leading underscore.
  public static final BuildRuleType AAPT_PACKAGE = ImmutableBuildRuleType.of("_aapt_package");
  public static final BuildRuleType COPY_NATIVE_LIBS =
      ImmutableBuildRuleType.of("_copy_native_libs");
  public static final BuildRuleType EXOPACKAGE_DEPS_ABI =
      ImmutableBuildRuleType.of("_exopackage_deps_abi");
  public static final BuildRuleType DUMMY_R_DOT_JAVA =
      ImmutableBuildRuleType.of("_dummy_r_dot_java");
  public static final BuildRuleType GWT_MODULE = ImmutableBuildRuleType.of("_gwt_module");
  public static final BuildRuleType RESOURCES_FILTER =
      ImmutableBuildRuleType.of("_resources_filter");
  public static final BuildRuleType PRE_DEX = ImmutableBuildRuleType.of("_pre_dex");
  public static final BuildRuleType DEX_MERGE = ImmutableBuildRuleType.of("_dex_merge");
  public static final BuildRuleType PACKAGE_STRING_ASSETS =
      ImmutableBuildRuleType.of("_package_string_assets");

  /**
   * @return the name as displayed in a build file, such as "java_library"
   */
  @Value.Parameter
  public abstract String getName();

  @Value.Derived
  public boolean isTestRule() {
    return getName().endsWith("_test");
  }

  @Value.Check
  protected void check() {
    String name = getName();
    Preconditions.checkArgument(name.toLowerCase().equals(name));
  }

  @Override
  public String toString() {
    return getName();
  }

}
