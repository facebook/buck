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

package com.facebook.buck.swift.toolchain;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/**
 * Expresses the target platform for Swift compilation. For more details, see the LLVM
 * documentation:
 *
 * <p>https://clang.llvm.org/docs/CrossCompilation.html#target-triple
 */
@BuckStyleValue
public abstract class SwiftTargetTriple implements AddsToRuleKey {
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

  public static SwiftTargetTriple of(
      String architecture, String vendor, String platformName, String targetSdkVersion) {
    return ImmutableSwiftTargetTriple.of(architecture, vendor, platformName, targetSdkVersion);
  }

  public SwiftTargetTriple withTargetSdkVersion(String targetSdkVersion) {
    if (targetSdkVersion.equals(getTargetSdkVersion())) {
      return this;
    }
    return of(getArchitecture(), getVendor(), getPlatformName(), targetSdkVersion);
  }
}
