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

package com.facebook.buck.intellij.ideabuck.api;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A project-level service that provides information about the project's buck's cell configuration.
 */
public interface BuckCellManager {

  static BuckCellManager getInstance(Project project) {
    return project.getComponent(BuckCellManager.class);
  }

  /** Information about a single buck cell. */
  interface Cell {
    /** Returns the name of the cell, if it is named. */
    Optional<String> getName();

    /**
     * Returns the name of the buildfile used by this cell.
     *
     * <p>By default, this is {@code BUCK}, but cells may override it by setting the buck config
     * property {@code buildfile.name}.
     *
     * @see <a href="https://buckbuild.com/concept/build_file.html">The buck documentation on build
     *     files</a>
     */
    String getBuildfileName();

    /** Returns the root directory for this cell as a {@link VirtualFile}. */
    Optional<VirtualFile> getRootDirectory();

    /** Returns the root directory for this cell as a {@link Path}. */
    Path getRootPath();
  }

  /**
   * Returns the default {@link Cell} for this project.
   *
   * <p>All buck commands for this project should be run from a working directory inside this cell.
   */
  Optional<? extends Cell> getDefaultCell();

  /** Returns all the cells for this project. */
  List<? extends Cell> getCells();

  /** Returns a {@link Cell} with a matching name, if it exists. */
  Optional<? extends Cell> findCellByName(String name);

  /** Returns the {@link Cell} that owns the given {@link VirtualFile}. */
  Optional<? extends Cell> findCellByVirtualFile(VirtualFile virtualFile);

  /** Returns the {@link Cell} that owns the given {@link Path}. */
  Optional<? extends Cell> findCellByPath(Path path);
}
