/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple.common;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/**
 * Expresses the compiler triple for Apple platforms when using Clang. For more details, see the
 * LLVM documentation at https://clang.llvm.org/docs/CrossCompilation.html#target-triple
 *
 * <p>The triple has the general format <arch><sub>-<vendor>-<sys>-<abi>, where: arch = x86_64,
 * i386, arm, etc. sub = v5, v6m, v7a, v7m, etc. vendor = apple sys = macosx, ios, watchos, etc. abi
 * = macabi (catalyst), etc. [optional]
 *
 * <p>Note that the "sys" component can optionally have a target SDK version.
 */
@BuckStyleValue
public abstract class AppleCompilerTargetTriple implements AddsToRuleKey {
  @AddToRuleKey
  public abstract String getArchitecture();

  @AddToRuleKey
  public abstract String getVendor();

  @AddToRuleKey
  public abstract String getPlatformName();

  @AddToRuleKey
  public abstract String getTargetSdkVersion();

  public String getTriple() {
    return getArchitecture() + "-" + getVendor() + "-" + getPlatformName() + getTargetSdkVersion();
  }

  public static AppleCompilerTargetTriple of(
      String architecture, String vendor, String platformName, String targetSdkVersion) {
    return ImmutableAppleCompilerTargetTriple.ofImpl(
        architecture, vendor, platformName, targetSdkVersion);
  }

  /** Creates a copy of the triple with a different SDK version. */
  public AppleCompilerTargetTriple withTargetSdkVersion(String targetSdkVersion) {
    if (targetSdkVersion.equals(getTargetSdkVersion())) {
      return this;
    }
    return of(getArchitecture(), getVendor(), getPlatformName(), targetSdkVersion);
  }
}