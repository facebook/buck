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

package com.facebook.buck.apple.xcode.xcodeproj;

import com.facebook.buck.util.Optionals;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.nio.file.Path;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Utility class representing a tuple of (SourceTree, Path) used for uniquely describing a file
 * reference in a group.
 */
public class SourceTreePath implements Comparable<SourceTreePath> {
  private final PBXReference.SourceTree sourceTree;
  private final Path path;
  private final Optional<String> defaultType;

  public SourceTreePath(
      PBXReference.SourceTree sourceTree,
      Path path,
      Optional<String> defaultType) {
    this.sourceTree = sourceTree;
    Preconditions.checkState(
        path.toString().length() > 0,
        "A path to a file cannot be null or empty");
    path = path.normalize();
    Preconditions.checkState(path.toString().length() > 0, "A path to a file cannot be empty");
    this.path = path;
    this.defaultType = defaultType;
  }

  public PBXReference.SourceTree getSourceTree() {
    return sourceTree;
  }

  public Path getPath() {
    return path;
  }

  public PBXFileReference createFileReference(String name) {
    return new PBXFileReference(name, path.toString(), sourceTree, defaultType);
  }

  public PBXFileReference createFileReference() {
    return createFileReference(path.getFileName().toString());
  }

  public XCVersionGroup createVersionGroup() {
    return new XCVersionGroup(
        path.getFileName().toString(),
        path.toString(),
        sourceTree);
  }

  @Override
  public int compareTo(SourceTreePath o) {
    int sourceTreeComparisonResult = getSourceTree().ordinal() - o.getSourceTree().ordinal();
    if (sourceTreeComparisonResult != 0) {
      return sourceTreeComparisonResult;
    }

    int pathComparisonResult = getPath().compareTo(o.getPath());
    if (pathComparisonResult != 0) {
      return pathComparisonResult;
    }

    return Optionals.compare(defaultType, o.defaultType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sourceTree, path, defaultType);
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (other == null || !(other instanceof SourceTreePath)) {
      return false;
    }

    SourceTreePath that = (SourceTreePath) other;
    return Objects.equals(this.sourceTree, that.sourceTree) &&
        Objects.equals(this.path, that.path) &&
        Objects.equals(this.defaultType, that.defaultType);
  }

  @Override
  public String toString() {
    return "$" + sourceTree + "/" + path;
  }
}
