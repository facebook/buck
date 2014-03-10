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
import java.nio.file.Path;
import java.util.Map;

public interface FilteredDirectoryCopier {

  /**
   * Copies multiple directories while filtering out individual files which fail a predicate.
   * @param sourcesToDestinations mapping from source to destination directories
   * @param pred predicate to test against
   */
  public abstract void copyDirs(
      ProjectFilesystem filesystem,
      Map<Path, Path> sourcesToDestinations,
      Predicate<Path> pred) throws IOException;

  /**
   * Creates a filtered copy of a directory.
   * @param srcDir source directory
   * @param destDir destination directory
   * @param pred a predicate to determine which files should be copied.
   */
  public abstract void copyDir(
      ProjectFilesystem filesystem,
      Path srcDir,
      Path destDir,
      Predicate<Path> pred) throws IOException;

}
