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

package com.facebook.buck.edenfs;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.FileSystemNotWatchedException;
import com.facebook.buck.io.watchman.ProjectWatch;
import com.facebook.buck.io.watchman.Watchman;
import java.nio.file.Path;
import java.nio.file.Paths;

/** A class wraps everything Eden needs from Watchman */
public class EdenWatchman {
  private final ProjectFilesystem projectFilesystem;
  private final Watchman watchman;
  private final Path watchmanRootPath;

  public EdenWatchman(Watchman watchman, ProjectFilesystem projectFilesystem) {
    this.watchman = watchman;
    this.projectFilesystem = projectFilesystem;
    ProjectWatch watch = watchman.getProjectWatches().get(projectFilesystem.getRootPath());
    if (watch == null) {
      String msg =
          String.format(
              "Path [%s] is not watched. The list of watched project: [%s]",
              projectFilesystem.getRootPath(), watchman.getProjectWatches().keySet());
      throw new FileSystemNotWatchedException(msg);
    }
    watchmanRootPath = Paths.get(watch.getWatchRoot());
  }

  public Watchman getWatchman() {
    return watchman;
  }

  public Path getWatchmanRootPath() {
    return watchmanRootPath;
  }

  public ProjectFilesystem getProjectFilesystem() {
    return projectFilesystem;
  }
}
