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

package com.facebook.buck.apple.toolchain;

import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

/** Metadata about an Apple SDK. */
@BuckStyleValueWithBuilder
public abstract class AppleSdk {
  /** The full name of the SDK. For example: {@code iphonesimulator8.0}. */
  public abstract String getName();

  /** The version number of the SDK. For example: {@code 8.0}. */
  public abstract String getVersion();

  /** The platform of the SDK. For example, {@code iphoneos}. */
  public abstract ApplePlatform getApplePlatform();

  /** The architectures supported by the SDK. For example: {@code [i386, x86_64]}. */
  public abstract Set<String> getArchitectures();

  public abstract Optional<String> getTargetTripleVendor();

  public abstract Optional<String> getTargetTriplePlatformName();

  public abstract Optional<String> getTargetTripleABI();

  /** Defines a list of SDK-specific system framework search paths. Passed using -iframework. */
  @Value.Default
  public ImmutableList<String> getAdditionalSystemFrameworkSearchPaths() {
    return ImmutableList.of();
  }

  /** Defines a list of SDK-specific library search paths. Passed using -L. */
  @Value.Default
  public ImmutableList<String> getAdditionalLibrarySearchPaths() {
    return ImmutableList.of();
  }

  /** Defines a list of SDK-specific system header search paths. Passed using -isystem. */
  @Value.Default
  public ImmutableList<String> getAdditionalSystemHeaderSearchPaths() {
    return ImmutableList.of();
  }

  /**
   * The toolchains used by the SDK. For example: {@code ["com.apple.dt.toolchain.XcodeDefault"]}
   */
  public abstract ImmutableList<AppleToolchain> getToolchains();

  public AppleSdk withName(String name) {
    if (getName().equals(name)) {
      return this;
    }
    return builder().from(this).setName(name).build();
  }

  public AppleSdk withVersion(String version) {
    if (getVersion().equals(version)) {
      return this;
    }
    return builder().from(this).setVersion(version).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableAppleSdk.Builder {}
}
