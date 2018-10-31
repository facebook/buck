/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.features.filebundler;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.PatternsMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public abstract class FileBundler {

  private final Path basePath;

  public FileBundler(BuildTarget target) {
    this(target.getBasePath());
  }

  public FileBundler(Path basePath) {
    this.basePath = Objects.requireNonNull(basePath);
  }

  private static void findAndAddRelativePathToMap(
      Path basePath,
      Path absoluteFilePath,
      Path relativeFilePath,
      Path assumedAbsoluteBasePath,
      Map<Path, Path> relativePathMap) {
    Path pathRelativeToBaseDir;

    if (relativeFilePath.startsWith(basePath) || basePath.equals(MorePaths.EMPTY_PATH)) {
      pathRelativeToBaseDir = MorePaths.relativize(basePath, relativeFilePath);
    } else {
      pathRelativeToBaseDir = assumedAbsoluteBasePath.relativize(absoluteFilePath);
    }

    if (relativePathMap.containsKey(pathRelativeToBaseDir)) {
      throw new HumanReadableException(
          "The file '%s' appears twice in the hierarchy", pathRelativeToBaseDir.getFileName());
    }
    relativePathMap.put(pathRelativeToBaseDir, absoluteFilePath);
  }

  static ImmutableMap<Path, Path> createRelativeMap(
      Path basePath,
      ProjectFilesystem filesystem,
      SourcePathResolver resolver,
      ImmutableSortedSet<SourcePath> toCopy) {
    Map<Path, Path> relativePathMap = new HashMap<>();

    for (SourcePath sourcePath : toCopy) {
      Path absoluteBasePath = resolver.getAbsolutePath(sourcePath);
      try {
        if (Files.isDirectory(absoluteBasePath)) {
          ImmutableSet<Path> files = filesystem.getFilesUnderPath(absoluteBasePath);
          Path absoluteBasePathParent = absoluteBasePath.getParent();

          for (Path file : files) {
            Path absoluteFilePath = filesystem.resolve(file);
            findAndAddRelativePathToMap(
                basePath, absoluteFilePath, file, absoluteBasePathParent, relativePathMap);
          }
        } else {
          findAndAddRelativePathToMap(
              basePath,
              absoluteBasePath,
              resolver.getRelativePath(sourcePath),
              absoluteBasePath.getParent(),
              relativePathMap);
        }
      } catch (IOException e) {
        throw new RuntimeException(
            String.format("Couldn't read directory [%s].", absoluteBasePath.toString()), e);
      }
    }

    return ImmutableMap.copyOf(relativePathMap);
  }

  public void copy(
      ProjectFilesystem filesystem,
      BuildCellRelativePathFactory buildCellRelativePathFactory,
      ImmutableList.Builder<Step> steps,
      Path destinationDir,
      ImmutableSortedSet<SourcePath> toCopy,
      SourcePathResolver pathResolver) {
    copy(
        filesystem,
        buildCellRelativePathFactory,
        steps,
        destinationDir,
        toCopy,
        pathResolver,
        PatternsMatcher.EMPTY);
  }

  public void copy(
      ProjectFilesystem filesystem,
      BuildCellRelativePathFactory buildCellRelativePathFactory,
      ImmutableList.Builder<Step> steps,
      Path destinationDir,
      ImmutableSortedSet<SourcePath> toCopy,
      SourcePathResolver pathResolver,
      PatternsMatcher entriesToExclude) {

    Map<Path, Path> relativeMap = createRelativeMap(basePath, filesystem, pathResolver, toCopy);

    for (Map.Entry<Path, Path> pathEntry : relativeMap.entrySet()) {
      Path relativePath = pathEntry.getKey();
      Path absolutePath = Objects.requireNonNull(pathEntry.getValue());
      Path destination = destinationDir.resolve(relativePath);

      if (entriesToExclude.hasPatterns()) {
        String entryPath = MorePaths.pathWithUnixSeparators(relativePath);
        if (entriesToExclude.matchesAny(entryPath)) {
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
