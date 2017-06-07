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

import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ComparisonChain;
import java.nio.file.Path;
import java.util.Objects;

public class PathSourcePath implements SourcePath {

  private final ProjectFilesystem filesystem;

  // A name for the path.  Used for equality, hashcode, and comparisons.
  private final String relativePathName;

  // A supplier providing the path.  Used to resolve to `Path` used to access the file.
  private final Supplier<Path> relativePath;

  public PathSourcePath(
      ProjectFilesystem filesystem, String relativePathName, Supplier<Path> relativePath) {
    this.filesystem = filesystem;
    this.relativePathName = relativePathName;
    this.relativePath = relativePath;
  }

  public PathSourcePath(ProjectFilesystem filesystem, Path relativePath) {
    this(filesystem, relativePath.toString(), Suppliers.ofInstance(relativePath));
  }

  @Override
  public int hashCode() {
    return Objects.hash(filesystem.getRootPath(), relativePathName);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PathSourcePath)) {
      return false;
    }
    PathSourcePath that = (PathSourcePath) other;
    return relativePathName.equals(that.relativePathName)
        && filesystem.getRootPath().equals(that.filesystem.getRootPath());
  }

  @Override
  public String toString() {
    return filesystem.getRootPath().resolve(relativePathName).toString();
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

    PathSourcePath that = (PathSourcePath) other;

    return ComparisonChain.start()
        .compare(filesystem.getRootPath(), that.filesystem.getRootPath())
        .compare(relativePathName, that.relativePathName)
        .result();
  }

  public ProjectFilesystem getFilesystem() {
    return filesystem;
  }

  public Path getRelativePath() {
    return relativePath.get();
  }
}
