/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.google.common.base.Predicate;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/**
 * This class allows the creation of copies of multiple directories, while filtering out files which
 * do not match a specified predicate.
 * <p>
 * Current caveats: <ul>
 *   <li>Existing content in destination directories is deleted.</li>
 *   <li>Empty directories will not be created.</li>
 * </ul>
 */
public class DefaultFilteredDirectoryCopier implements FilteredDirectoryCopier {

  private static final DefaultFilteredDirectoryCopier instance =
      new DefaultFilteredDirectoryCopier();

  public static DefaultFilteredDirectoryCopier getInstance() {
    return instance;
  }

  private DefaultFilteredDirectoryCopier() {

  }

  @Override
  public void copyDirs(ProjectFilesystem filesystem,
      Map<Path, Path> sourcesToDestinations,
      Predicate<Path> pred) throws IOException {
    for (Map.Entry<Path, Path> e : sourcesToDestinations.entrySet()) {
      copyDir(filesystem, e.getKey(), e.getValue(), pred);
    }
  }

  @Override
  public void copyDir(final ProjectFilesystem filesystem,
      final Path srcDir,
      final Path destDir,
      final Predicate<Path> pred) throws IOException {

    // Remove existing contents if any.
    if (filesystem.exists(destDir)) {
      filesystem.rmdir(destDir);
    }
    filesystem.mkdirs(destDir);

    filesystem.walkRelativeFileTree(srcDir, new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path srcPath, BasicFileAttributes attributes)
              throws IOException {
            if (pred.apply(srcPath)) {
              Path destPath = destDir.resolve(srcDir.relativize(srcPath));
              filesystem.createParentDirs(destPath);
              filesystem.copy(srcPath, destPath, ProjectFilesystem.CopySourceMode.FILE);
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
