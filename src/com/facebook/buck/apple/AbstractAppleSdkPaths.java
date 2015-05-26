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
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.Set;

/**
 * Paths to Apple SDK directories under an installation of Xcode.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractAppleSdkPaths {
  private static final ImmutableSet<PBXReference.SourceTree> SUPPORTED_SOURCE_TREES =
      ImmutableSet.of(
          PBXReference.SourceTree.PLATFORM_DIR,
          PBXReference.SourceTree.SDKROOT,
          PBXReference.SourceTree.DEVELOPER_DIR);

  /**
   * Absolute path to the active DEVELOPER_DIR.
   *
   * Example:
   *
   * {@code /Applications/Xcode.app/Contents/Developer}
   */
  public abstract Optional<Path> getDeveloperPath();

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

  public Function<PBXReference.SourceTree, Path> sourceTreeRootsFunction() {
    return new Function<PBXReference.SourceTree, Path>() {
      @Override
      public Path apply(final PBXReference.SourceTree sourceTree) {
        if (sourceTree.equals(PBXReference.SourceTree.SDKROOT)) {
          return getSdkPath();
        } else if (sourceTree.equals(PBXReference.SourceTree.PLATFORM_DIR)) {
          return getPlatformPath();
        } else if (sourceTree.equals(PBXReference.SourceTree.DEVELOPER_DIR)) {
          Optional<Path> developerPath = getDeveloperPath();
          if (!developerPath.isPresent()) {
            throw new HumanReadableException(
                "DEVELOPER_DIR source tree unavailable without developer dir");
          }

          return developerPath.get();
        }
        throw new HumanReadableException("Unsupported source tree: '%s'", sourceTree);
      }
    };
  }

  public Path resolve(SourceTreePath path) {
    return sourceTreeRootsFunction().apply(path.getSourceTree()).resolve(path.getPath());
  }

  public Function<SourceTreePath, Path> resolveFunction() {
    return new Function<SourceTreePath, Path>() {
      @Override
      public Path apply(SourceTreePath input) {
        return resolve(input);
      }
    };
  }

  public Function<String, String> replaceSourceTreeReferencesFunction() {
    return new Function<String, String>() {
      @Override
      public String apply(String input) {
        Function<PBXReference.SourceTree, Path> getSourceTreeRoot = sourceTreeRootsFunction();
        for (PBXReference.SourceTree sourceTree : SUPPORTED_SOURCE_TREES) {
          input = input.replace(
              "$" + sourceTree.toString(),
              getSourceTreeRoot.apply(sourceTree).toString());
        }
        return input;
      }
    };
  }
}
