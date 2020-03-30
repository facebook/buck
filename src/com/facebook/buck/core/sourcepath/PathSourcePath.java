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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ComparisonChain;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

@BuckStylePrehashedValue
public abstract class PathSourcePath implements SourcePath {

  public abstract ProjectFilesystem getFilesystem();

  public abstract Path getRelativePath();

  @Override
  public int hashCode() {
    return Objects.hash(getFilesystem().getRootPath(), getRelativePath());
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
    return getRelativePath().equals(that.getRelativePath())
        && getFilesystem().getRootPath().equals(that.getFilesystem().getRootPath());
  }

  @Override
  public String toString() {
    return getFilesystem().getRootPath().resolve(getRelativePath().toString()).toString();
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
        .compare(
            getFilesystem().getRootPath(), that.getFilesystem().getRootPath(), AbsPath.comparator())
        .compare(getRelativePath(), that.getRelativePath())
        .result();
  }

  public static PathSourcePath of(ProjectFilesystem filesystem, Path relativePath) {
    // TODO(nga): path is not always relative
    return ImmutablePathSourcePath.of(filesystem, relativePath);
  }

  public static PathSourcePath of(ProjectFilesystem filesystem, ForwardRelativePath relativePath) {
    return of(filesystem, relativePath.toPath(filesystem.getFileSystem()));
  }

  public static PathSourcePath of(ProjectFilesystem filesystem, RelPath relativePath) {
    return of(filesystem, relativePath.getPath());
  }

  /** @return the {@link PathSourcePath} backing the given {@link SourcePath}, if any. */
  public static Optional<PathSourcePath> from(SourcePath sourcePath) {
    if (sourcePath instanceof ArchiveMemberSourcePath) {
      sourcePath = ((ArchiveMemberSourcePath) sourcePath).getArchiveSourcePath();
    }
    return sourcePath instanceof PathSourcePath
        ? Optional.of((PathSourcePath) sourcePath)
        : Optional.empty();
  }
}
