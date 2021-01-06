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

package com.facebook.buck.core.sourcepath;

import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;

/**
 * Like {@link com.facebook.buck.core.sourcepath.SourcePath} but when configuration is not yet
 * available.
 */
public abstract class UnconfiguredSourcePath {

  private UnconfiguredSourcePath() {}

  /** {@link CellRelativePath} variant of source path. */
  public static class Path extends UnconfiguredSourcePath {
    private final CellRelativePath path;

    public Path(CellRelativePath path) {
      this.path = path;
    }

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.path(path);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      return path.equals(((Path) o).path);
    }

    @Override
    public int hashCode() {
      return path.hashCode();
    }

    @Override
    public String toString() {
      return path.toString();
    }
  }

  /** {@link UnconfiguredBuildTargetWithOutputs} variant of source path. */
  public static class BuildTarget extends UnconfiguredSourcePath {
    private final UnconfiguredBuildTargetWithOutputs target;

    public BuildTarget(UnconfiguredBuildTargetWithOutputs target) {
      this.target = target;
    }

    @Override
    public <R> R match(Matcher<R> matcher) {
      return matcher.buildTarget(target);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      return target.equals(((BuildTarget) o).target);
    }

    @Override
    public int hashCode() {
      return target.hashCode();
    }

    @Override
    public String toString() {
      return target.toString();
    }
  }

  /** Callback for {@link #match(Matcher)} operation. */
  public interface Matcher<R> {
    R path(CellRelativePath path);

    R buildTarget(UnconfiguredBuildTargetWithOutputs target);
  }

  /**
   * Type-safe switch between source path variants (@link {@link UnconfiguredSourcePath}
   * subclasses).
   */
  public abstract <R> R match(Matcher<R> matcher);
}
