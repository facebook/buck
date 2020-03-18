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

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.CellRelativePath;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetWithOutputs;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Preconditions;

/**
 * Like {@link com.facebook.buck.core.sourcepath.SourcePath} but when configuration is not yet
 * available.
 */
public abstract class UnconfiguredSourcePath implements Comparable<UnconfiguredSourcePath> {

  private UnconfiguredSourcePath() {}

  /** {@link CellRelativePath} variant of source path. */
  @JsonSerialize
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
  @JsonSerialize
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

  /** Apply a configuration to this source path. */
  public SourcePath configure(
      CellNameResolver cellNameResolver,
      ProjectFilesystem projectFilesystem,
      TargetConfiguration targetConfiguration) {
    return this.match(
        new UnconfiguredSourcePath.Matcher<SourcePath>() {
          @Override
          public SourcePath path(CellRelativePath path) {
            Preconditions.checkState(path.getCellName() == cellNameResolver.getCurrentCellName());
            return PathSourcePath.of(
                projectFilesystem, path.getPath().toRelPath(projectFilesystem.getFileSystem()));
          }

          @Override
          public SourcePath buildTarget(UnconfiguredBuildTargetWithOutputs target) {
            return DefaultBuildTargetSourcePath.of(target.configure(targetConfiguration));
          }
        });
  }

  @Override
  public int compareTo(UnconfiguredSourcePath o) {
    if (this.getClass() != o.getClass()) {
      return this.getClass().getName().compareTo(o.getClass().getName());
    }
    if (this instanceof BuildTarget) {
      BuildTarget thisBuildTarget = (BuildTarget) this;
      BuildTarget thatBuildTarget = (BuildTarget) o;
      return thisBuildTarget.target.compareTo(thatBuildTarget.target);
    } else if (this instanceof Path) {
      Path thisPath = (Path) this;
      Path thatPath = (Path) o;
      return thisPath.path.compareTo(thatPath.path);
    } else {
      throw new AssertionError();
    }
  }
}
