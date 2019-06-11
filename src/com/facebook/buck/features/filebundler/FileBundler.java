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
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.PatternsMatcher;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public abstract class FileBundler {

  private final Path basePath;

  public FileBundler(ProjectFilesystem filesystem, BuildTarget target) {
    this(filesystem.resolve(target.getBasePath()));
  }

  public FileBundler(Path basePath) {
    this.basePath = Objects.requireNonNull(basePath);
  }

  static ImmutableMap<Path, Path> createRelativeMap(
      Path basePath,
      ProjectFilesystem filesystem,
      SourcePathResolver resolver,
      Iterable<SourcePath> toCopy) {
    // The goal here is pretty simple.
    // 1. For a PathSourcePath (an explicit file reference in a BUCK file) that is a
    //   a. file, add it as a single entry at a path relative to this target's base path
    //   b. directory, add all its contents as paths relative to this target's base path
    // 2. For a BuildTargetSourcePath (an output of another rule) that is a
    //   a. file, add it as a single entry with just the filename
    //   b. directory, add all its as paths relative to that directory preceded by the directory
    // name
    //
    // Simplified: 1a and 1b add the item relative to the target's directory, 2a and 2b add the item
    // relative to its own parent.

    // TODO(cjhopman): We should remove 1a because we shouldn't allow specifying directories in
    // srcs.

    Map<Path, Path> relativePathMap = new LinkedHashMap<>();

    for (SourcePath sourcePath : toCopy) {
      Path absolutePath = resolver.getAbsolutePath(sourcePath).normalize();
      try {
        if (sourcePath instanceof PathSourcePath) {
          // If the path doesn't start with the base path, then it's a reference to a file in a
          // different package and violates package boundaries. We could just add it by the
          // filename, but better to discourage violating package boundaries.
          Verify.verify(
              absolutePath.startsWith(basePath),
              "Expected %s to start with %s.",
              absolutePath,
              basePath);
          addPathToRelativePathMap(
              filesystem,
              relativePathMap,
              basePath,
              absolutePath,
              basePath.relativize(absolutePath));
        } else {
          addPathToRelativePathMap(
              filesystem,
              relativePathMap,
              absolutePath.getParent(),
              absolutePath,
              absolutePath.getFileName());
        }
      } catch (IOException e) {
        throw new RuntimeException(
            String.format("Couldn't read directory [%s].", absolutePath.toString()), e);
      }
    }

    return ImmutableMap.copyOf(relativePathMap);
  }

  private static void addPathToRelativePathMap(
      ProjectFilesystem filesystem,
      Map<Path, Path> relativePathMap,
      Path basePath,
      Path absolutePath,
      Path relativePath)
      throws IOException {
    if (Files.isDirectory(absolutePath)) {
      ImmutableSet<Path> files = filesystem.getFilesUnderPath(absolutePath);
      for (Path file : files) {
        Path absoluteFilePath = filesystem.resolve(file).normalize();
        addToRelativePathMap(
            relativePathMap, basePath.relativize(absoluteFilePath), absoluteFilePath);
      }
    } else {
      addToRelativePathMap(relativePathMap, relativePath, absolutePath);
    }
  }

  private static void addToRelativePathMap(
      Map<Path, Path> relativePathMap, Path pathRelativeToBaseDir, Path absoluteFilePath) {
    relativePathMap.compute(
        pathRelativeToBaseDir,
        (ignored, current) -> {
          if (current != null) {
            throw new HumanReadableException(
                "The file '%s' appears twice in the hierarchy",
                pathRelativeToBaseDir.getFileName());
          }
          return absoluteFilePath;
        });
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
        String entryPath = PathFormatter.pathWithUnixSeparators(relativePath);
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
