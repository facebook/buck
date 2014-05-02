/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.rules.BuildRuleType;
import com.google.common.collect.ImmutableList;

/**
 * Helpers for reading properties of Apple target build rules.
 */
public final class AppleBuildRules {

  // Utility class not to be instantiated.
  private AppleBuildRules() { }

  private static final ImmutableList<BuildRuleType> XCODE_TARGET_BUILD_RULE_TYPES =
      ImmutableList.of(
          IosBinaryDescription.TYPE,
          IosLibraryDescription.TYPE,
          IosTestDescription.TYPE,
          MacosxBinaryDescription.TYPE,
          MacosxFrameworkDescription.TYPE);

  private static final ImmutableList<BuildRuleType> XCODE_TARGET_BUILD_RULE_TEST_TYPES =
      ImmutableList.of(IosTestDescription.TYPE);

  /**
   * Whether the build rule type is equivalent to some kind of Xcode target.
   */
  public static boolean isXcodeTargetBuildRuleType(BuildRuleType type) {
    return XCODE_TARGET_BUILD_RULE_TYPES.contains(type);
  }

  /**
   * Whether the build rule type is a test target.
   */
  public static boolean isXcodeTargetTestBuildRuleType(BuildRuleType type) {
    return XCODE_TARGET_BUILD_RULE_TEST_TYPES.contains(type);
  }
}
