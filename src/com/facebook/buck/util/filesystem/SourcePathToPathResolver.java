/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.util.filesystem;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Utility to convert {@link SourcePath} to Relative Path */
public class SourcePathToPathResolver {
  private SourcePathToPathResolver() {}

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

  /** Converts a set of {@link SourcePath} to relative paths from the {@code basePath} */
  public static ImmutableMap<Path, Path> createRelativeMap(
      Path basePath,
      ProjectFilesystem filesystem,
      SourcePathResolver resolver,
      ImmutableSortedSet<SourcePath> sourcePaths) {
    Map<Path, Path> relativePathMap = new HashMap<>();

    for (SourcePath sourcePath : sourcePaths) {
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
}
