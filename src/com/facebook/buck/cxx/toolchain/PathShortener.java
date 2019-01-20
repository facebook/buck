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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.base.Preconditions;
import java.nio.file.Path;

/** A function that may shorten a given path using variou strategies. */
public interface PathShortener {
  Path shorten(Path absolutePath);

  static PathShortener byRelativizingToWorkingDir(Path workingDir) {
    return (absolutePath) -> {
      Preconditions.checkState(
          absolutePath.isAbsolute(),
          "Expected preprocessor suffix to be absolute: %s",
          absolutePath);
      Path relativePath = MorePaths.relativize(workingDir, absolutePath);
      return absolutePath.toString().length() > relativePath.toString().length()
          ? relativePath
          : absolutePath;
    };
  }

  static PathShortener byRelativizingToWorkingDir(PathSourcePath workingDir) {
    return byRelativizingToWorkingDir(
        workingDir.getFilesystem().resolve(workingDir.getRelativePath()));
  }

  static PathShortener identity() {
    return x -> x;
  }
}
