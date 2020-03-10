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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.core.sourcepath.UnconfiguredSourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Unconfigured graph version of {@link com.facebook.buck.rules.coercer.FrameworkPath}. */
abstract class UnconfiguredFrameworkPath {
  private UnconfiguredFrameworkPath() {}

  /** Framework path is a source path. */
  @BuckStyleValue
  abstract static class SourcePathFp extends UnconfiguredFrameworkPath {
    public abstract UnconfiguredSourcePath getSourcePath();

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.sourcePath(getSourcePath());
    }
  }

  /** Framework path is a source tree path. */
  @BuckStyleValue
  abstract static class SourceTreePathFp extends UnconfiguredFrameworkPath {
    public abstract SourceTreePath getSourceTreePath();

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.sourceTreePath(getSourceTreePath());
    }
  }

  /** Construct from a source path. */
  public static UnconfiguredFrameworkPath ofSourcePath(UnconfiguredSourcePath sourcePath) {
    return ImmutableSourcePathFp.ofImpl(sourcePath);
  }

  /** Construct from a source tree path. */
  public static UnconfiguredFrameworkPath ofSourceTreePath(SourceTreePath sourceTreePath) {
    return ImmutableSourceTreePathFp.ofImpl(sourceTreePath);
  }

  /** {@link #match(Matcher)} callback. */
  public interface Matcher<R> {
    /** This is a source path. */
    R sourcePath(UnconfiguredSourcePath sourcePath);

    /** This is a source tree path. */
    R sourceTreePath(SourceTreePath sourceTreePath);
  }

  /** Invoke a different callback depending on this subclass. */
  public abstract <R> R match(Matcher<R> matcher);
}
