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

package com.facebook.buck.features.filebundler;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.PatternsMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public abstract class FileBundler {

  private final Path basePath;

  public FileBundler(ProjectFilesystem filesystem, BuildTarget target) {
    this(
        filesystem.resolve(
            target.getCellRelativeBasePath().getPath().toPath(filesystem.getFileSystem())));
  }

  public FileBundler(Path basePath) {
    this.basePath = Objects.requireNonNull(basePath);
  }

  public void copy(
      ProjectFilesystem filesystem,
      BuildCellRelativePathFactory buildCellRelativePathFactory,
      ImmutableList.Builder<Step> steps,
      Path destinationDir,
      ImmutableSortedSet<SourcePath> toCopy,
      SourcePathResolverAdapter pathResolver) {
    copy(
        filesystem,
        buildCellRelativePathFactory,
        steps,
        destinationDir,
        toCopy,
        pathResolver,
        PatternsMatcher.NONE);
  }

  public void copy(
      ProjectFilesystem filesystem,
      BuildCellRelativePathFactory buildCellRelativePathFactory,
      ImmutableList.Builder<Step> steps,
      Path destinationDir,
      Iterable<SourcePath> toCopy,
      SourcePathResolverAdapter pathResolver,
      PatternsMatcher entriesToExclude) {

    Map<Path, Path> relativeMap = pathResolver.createRelativeMap(basePath, toCopy);

    for (Map.Entry<Path, Path> pathEntry : relativeMap.entrySet()) {
      Path relativePath = pathEntry.getKey();
      Path absolutePath = Objects.requireNonNull(pathEntry.getValue());
      Path destination = destinationDir.resolve(relativePath);

      if (!entriesToExclude.isMatchesNone()) {
        String entryPath = PathFormatter.pathWithUnixSeparators(relativePath);
        if (entriesToExclude.matches(entryPath)) {
          continue;
        }
      }

      addCopySteps(
          filesystem, buildCellRelativePathFactory, steps, relativePath, absolutePath, destination);
    }
  }

  protected abstract void addCopySteps(
      ProjectFilesystem filesystem,
      BuildCellRelativePathFactory buildCellRelativePathFactory,
      ImmutableList.Builder<Step> steps,
      Path relativePath,
      Path absolutePath,
      Path destination);
}
