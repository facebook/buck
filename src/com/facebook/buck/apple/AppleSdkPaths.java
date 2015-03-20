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

import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.Set;

/**
 * Paths to Apple SDK directories under an installation of Xcode.
 */
@Value.Immutable
@BuckStyleImmutable
public abstract class AppleSdkPaths {
  /**
   * Absolute path to the active DEVELOPER_DIR.
   *
   * Example:
   *
   * {@code /Applications/Xcode.app/Contents/Developer}
   */
  public abstract Path getDeveloperPath();

  /**
   * Absolute paths to tools and files independent of the platform.
   *
   * Example:
   *
   * {@code [/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain]}
   */
  public abstract Set<Path> getToolchainPaths();

  /**
   * Absolute path to tools and files which depend on a particular platform.
   *
   * Example:
   *
   * {@code /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform}
   */
  public abstract Path getPlatformPath();

  /**
   * Absolute path to tools and files which depend on a particular SDK on a particular platform.
   *
   * Example:
   *
   * {@code /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk}
   */
  public abstract Path getSdkPath();

  public Path resolve(SourceTreePath path) {
    if (path.getSourceTree().equals(PBXReference.SourceTree.SDKROOT)) {
      return getSdkPath().resolve(path.getPath());
    } else if (path.getSourceTree().equals(PBXReference.SourceTree.PLATFORM_DIR)) {
      return getPlatformPath().resolve(path.getPath());
    } else if (path.getSourceTree().equals(PBXReference.SourceTree.DEVELOPER_DIR)) {
      return getDeveloperPath().resolve(path.getPath());
    }
    throw new HumanReadableException("Unsupported source tree: '%s'", path.getSourceTree());
  }

  public Function<SourceTreePath, Path> resolveFunction() {
    return new Function<SourceTreePath, Path>() {
      @Override
      public Path apply(SourceTreePath input) {
        return resolve(input);
      }
    };
  }
}
