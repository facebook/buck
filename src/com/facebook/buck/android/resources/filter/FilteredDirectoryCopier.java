/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android.resources.filter;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.function.Predicate;

/**
 * This class allows the creation of copies of multiple directories, while filtering out files which
 * do not match a specified predicate.
 *
 * <p>Current caveats:
 *
 * <ul>
 *   <li>Existing content in destination directories is deleted.
 *   <li>Empty directories will not be created.
 * </ul>
 */
public class FilteredDirectoryCopier {

  private FilteredDirectoryCopier() {}

  public static void copyDirs(
      AbsPath projectRoot, Map<Path, Path> sourcesToDestinations, Predicate<Path> pred)
      throws IOException {
    for (Map.Entry<Path, Path> e : sourcesToDestinations.entrySet()) {
      copyDir(projectRoot, e.getKey(), e.getValue(), pred);
    }
  }

  private static void copyDir(AbsPath projectRoot, Path srcDir, Path destDir, Predicate<Path> pred)
      throws IOException {

    // Remove existing contents if any.
    if (ProjectFilesystemUtils.exists(projectRoot, destDir)) {
      ProjectFilesystemUtils.deleteRecursivelyIfExists(projectRoot, destDir);
    }
    ProjectFilesystemUtils.mkdirs(projectRoot, destDir);

    ProjectFilesystemUtils.walkRelativeFileTree(
        projectRoot,
        srcDir,
        ProjectFilesystemUtils.getDefaultVisitOptions(),
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path srcPath, BasicFileAttributes attributes)
              throws IOException {
            if (pred.test(srcPath)) {
              Path destPath = destDir.resolve(srcDir.relativize(srcPath));
              ProjectFilesystemUtils.createParentDirs(projectRoot, destPath);
              ProjectFilesystemUtils.copy(projectRoot, srcPath, destPath, CopySourceMode.FILE);
            }
            return FileVisitResult.CONTINUE;
          }
        },
        ProjectFilesystemUtils.getEmptyIgnoreFilter());
  }
}
