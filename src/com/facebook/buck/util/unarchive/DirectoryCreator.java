/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.util.unarchive;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Simple class that creates directories, but keeps track of what it has created to ensure that it
 * does not create directories more than once. This is useful when extracting archives that may or
 * may not have duplicate entries, or to just ensure that a directory always exists before writing
 * out a new file
 */
public class DirectoryCreator {

  private final ProjectFilesystem filesystem;
  private Set<Path> existing = new HashSet<>();

  /**
   * Creates an instance of {@link DirectoryCreator}
   *
   * @param filesystem The filesystem to operate on
   */
  public DirectoryCreator(ProjectFilesystem filesystem) {
    this.filesystem = filesystem;
    existing.add(filesystem.getRootPath());
  }

  /**
   * Get the set of directories that were either created by this class, or manually specified in
   * {@link DirectoryCreator#recordPath(Path)}
   *
   * @return Set of directories
   */
  public Set<Path> recordedDirectories() {
    return existing;
  }

  /**
   * Ensure that we record that a path was created. This can be useful if for some reason a
   * directory is created externally, but the list of created directories is pulled from an instance
   * of {@link DirectoryCreator}
   *
   * @param target
   */
  public void recordPath(Path target) {
    existing.add(target);
  }

  public ProjectFilesystem getFilesystem() {
    return filesystem;
  }

  /**
   * Create a directory and all of its parents
   *
   * @param target The path where a directory should exist
   * @throws IOException If the directory could not be created
   */
  public void mkdirs(Path target) throws IOException {
    if (existing.contains(target)) {
      return;
    }
    filesystem.mkdirs(target);
    while (target != null) {
      existing.add(target);
      target = target.getParent();
    }
  }

  /**
   * Force a directory to be created. This will delete existing files (not directories) if they
   * share the name of the directory, including parent paths
   *
   * @param target The path where a directory should exist
   * @throws IOException The directory could not be created, or files that needed to be removed
   *     could not be removed
   */
  public void forcefullyCreateDirs(Path target) throws IOException {
    if (existing.contains(target)) {
      return;
    }
    if (filesystem.exists(target)) {
      if (!filesystem.isDirectory(target)) {
        filesystem.deleteFileAtPath(target);
        mkdirs(target);
      }
    } else {
      if (target.getParent() != null) {
        forcefullyCreateDirs(target.getParent());
      }
      mkdirs(target);
    }
    existing.add(target);
  }
}
