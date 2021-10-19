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

package com.facebook.buck.core.sourcepath.resolver.impl.utils;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Utilities method related to process relative path maps */
public class RelativePathMapUtils {

  /** Adds path to relative map */
  public static void addPathToRelativePathMap(
      AbsPath rootPath,
      Map<Path, AbsPath> relativePathMap,
      Path basePath,
      AbsPath absolutePath,
      Path relativePath)
      throws IOException {
    if (Files.isDirectory(absolutePath.getPath())) {
      ImmutableSet<Path> files =
          ProjectFilesystemUtils.getFilesUnderPath(
              rootPath,
              absolutePath.getPath(),
              ProjectFilesystemUtils.getDefaultVisitOptions(),
              ProjectFilesystemUtils.getEmptyIgnoreFilter());
      for (Path file : files) {
        AbsPath absoluteFilePath = rootPath.resolve(file).normalize();
        addToRelativePathMap(
            relativePathMap, basePath.relativize(absoluteFilePath.getPath()), absoluteFilePath);
      }
    } else {
      addToRelativePathMap(relativePathMap, relativePath, absolutePath);
    }
  }

  private static void addToRelativePathMap(
      Map<Path, AbsPath> relativePathMap, Path pathRelativeToBaseDir, AbsPath absoluteFilePath) {
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
}
