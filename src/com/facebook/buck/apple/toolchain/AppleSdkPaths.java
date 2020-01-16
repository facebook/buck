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
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

/** Paths to Apple SDK directories under an installation of Xcode. */
@BuckStyleValueWithBuilder
public abstract class AppleSdkPaths {
  /**
   * Absolute path to the active DEVELOPER_DIR.
   *
   * <p>Example:
   *
   * <p>{@code /Applications/Xcode.app/Contents/Developer}
   */
  public abstract Optional<Path> getDeveloperPath();

  /**
   * Absolute paths to tools and files independent of the platform.
   *
   * <p>Example:
   *
   * <p>{@code [/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain]}
   */
  public abstract ImmutableSet<Path> getToolchainPaths();

  /**
   * Absolute path to tools and files which depend on a particular platform.
   *
   * <p>Example:
   *
   * <p>{@code /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform}
   */
  public abstract Path getPlatformPath();

  /**
   * Absolute path to tools and files which depend on a particular SDK on a particular platform.
   *
   * <p>Example:
   *
   * <p>{@code
   * /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk}
   */
  public abstract Path getSdkPath();

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableAppleSdkPaths.Builder {}
}
