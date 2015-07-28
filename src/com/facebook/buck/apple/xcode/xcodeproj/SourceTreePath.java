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
  private final boolean isFolder;

  private SourceTreePath(PBXReference.SourceTree sourceTree, Path path, boolean isFolder) {
    this.sourceTree = sourceTree;
    Preconditions.checkState(
        path.toString().length() > 0,
        "A path to a file cannot be null or empty");
    path = path.normalize();
    Preconditions.checkState(path.toString().length() > 0, "A path to a file cannot be empty");
    this.path = path;
    this.isFolder = isFolder;
  }

  public PBXReference.SourceTree getSourceTree() {
    return sourceTree;
  }

  public Path getPath() {
    return path;
  }

  public boolean getIsFolder() {
    return isFolder;
  }

  public static SourceTreePath ofAutodetectedType(PBXReference.SourceTree sourceTree, Path path) {
    return new SourceTreePath(sourceTree, path, false);
  }

  public static SourceTreePath ofFolderType(PBXReference.SourceTree sourceTree, Path path) {
    return new SourceTreePath(sourceTree, path, true);
  }

  public PBXFileReference createFileReference(String name) {
    if (isFolder) {
      return PBXFileReference.ofFolderType(
          name,
          path.toString(),
          sourceTree);
    } else {
      return PBXFileReference.ofAutodetectedType(
          name,
          path.toString(),
          sourceTree);
    }
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

    return ((Boolean) getIsFolder()).compareTo(o.getIsFolder());
  }

  @Override
  public int hashCode() {
    return Objects.hash(sourceTree, path, isFolder);
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (other == null || !(other instanceof SourceTreePath)) {
      return false;
    }

    SourceTreePath that = (SourceTreePath) other;
    return Objects.equals(this.sourceTree, that.sourceTree) &&
        Objects.equals(this.path, that.path) &&
        this.isFolder == that.isFolder;
  }

  @Override
  public String toString() {
    return "$" + sourceTree + "/" + path;
  }
}
