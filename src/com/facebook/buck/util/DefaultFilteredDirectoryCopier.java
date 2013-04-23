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
import com.google.common.base.Throwables;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
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

  private static final DefaultFilteredDirectoryCopier instance
      = new DefaultFilteredDirectoryCopier();

  public static DefaultFilteredDirectoryCopier getInstance() {
    return instance;
  }

  private DefaultFilteredDirectoryCopier() {

  }

  @Override
  public void copyDirs(Map<String, String> sourcesToDestinations,
      Predicate<File> pred,
      ProcessExecutor processExecutor) {
    for (Map.Entry<String, String> e : sourcesToDestinations.entrySet()) {
      copyDir(e.getKey(), e.getValue(), pred, processExecutor);
    }
  }

  @Override
  public void copyDir(
      String srcDir,
      String destDir,
      final Predicate<File> pred,
      ProcessExecutor processExecutor) {
    final File dest = new File(destDir);

    // Remove existing contents if any.
    if (dest.exists()) {
      try {
        MoreFiles.rmdir(dest.getAbsolutePath(), processExecutor);
      } catch (IOException e) {
        throw Throwables.propagate(e);
      }
    }
    dest.mkdirs();

    // Copy filtered contents.
    new DirectoryTraversal(new File(srcDir)) {
      @Override
      public void visit(File srcFile, String relativePath) {
        if (pred.apply(srcFile)) {
          try {
            File destFile = new File(dest, relativePath);
            Files.createParentDirs(destFile);
            Files.copy(srcFile, destFile);
          } catch (IOException e) {
            throw Throwables.propagate(e);
          }
        }
      }
    }.traverse();
  }
}
