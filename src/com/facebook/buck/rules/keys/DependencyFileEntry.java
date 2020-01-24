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

package com.facebook.buck.rules.keys;

import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@BuckStyleValue
@JsonSerialize
@JsonDeserialize(as = ImmutableDependencyFileEntry.class)
public abstract class DependencyFileEntry {

  public abstract Path pathToFile();

  public abstract Optional<Path> pathWithinArchive();

  @Value.Check
  protected void check() {
    Preconditions.checkState(!pathToFile().isAbsolute());
    Preconditions.checkState(
        !pathWithinArchive().isPresent() || !pathWithinArchive().get().isAbsolute());
  }

  /**
   * Gets the path to the file. This would be the value of DependencyFileEntry.pathToFile() if the
   * SourcePath is converted to a DependencyFileEntry.
   */
  public static Path getPathToFile(SourcePathResolverAdapter resolver, SourcePath sourcePath) {
    if (sourcePath instanceof ArchiveMemberSourcePath) {
      return resolver.getRelativePath(
          ((ArchiveMemberSourcePath) sourcePath).getArchiveSourcePath());
    }
    return resolver.getRelativePath(sourcePath);
  }

  public static DependencyFileEntry fromSourcePath(
      SourcePath sourcePath, SourcePathResolverAdapter resolver) {
    Optional<Path> pathWithinArchive = Optional.empty();
    if (sourcePath instanceof ArchiveMemberSourcePath) {
      pathWithinArchive = Optional.of(((ArchiveMemberSourcePath) sourcePath).getMemberPath());
    }
    return DependencyFileEntry.of(getPathToFile(resolver, sourcePath), pathWithinArchive);
  }

  public static DependencyFileEntry of(
      Path pathToFile, Optional<? extends Path> pathWithinArchive) {
    return ImmutableDependencyFileEntry.of(pathToFile, pathWithinArchive);
  }

  public static DependencyFileEntry of(Path pathToFile) {
    return of(pathToFile, Optional.empty());
  }
}
