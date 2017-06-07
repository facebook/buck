/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Pair;
import com.google.common.collect.ComparisonChain;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A {@link BuildTargetSourcePath} which resolves to a specific (possibly non-default) output of the
 * {@link BuildRule} referred to by its target.
 */
public class ExplicitBuildTargetSourcePath extends BuildTargetSourcePath {

  private final Path resolvedPath;

  public ExplicitBuildTargetSourcePath(BuildTarget target, Path path) {
    super(target);
    this.resolvedPath = path;
  }

  public Path getResolvedPath() {
    return resolvedPath;
  }

  @Override
  public int hashCode() {
    return Objects.hash(getTarget(), resolvedPath);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof ExplicitBuildTargetSourcePath)) {
      return false;
    }

    ExplicitBuildTargetSourcePath that = (ExplicitBuildTargetSourcePath) other;
    return getTarget().equals(that.getTarget()) && resolvedPath.equals(that.resolvedPath);
  }

  @Override
  public String toString() {
    return String.valueOf(new Pair<>(getTarget(), resolvedPath));
  }

  @Override
  public int compareTo(SourcePath other) {
    if (other == this) {
      return 0;
    }

    int classComparison = compareClasses(other);
    if (classComparison != 0) {
      return classComparison;
    }

    ExplicitBuildTargetSourcePath that = (ExplicitBuildTargetSourcePath) other;

    return ComparisonChain.start()
        .compare(getTarget(), that.getTarget())
        .compare(resolvedPath, that.resolvedPath)
        .result();
  }
}
