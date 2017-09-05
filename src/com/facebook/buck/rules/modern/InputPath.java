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

package com.facebook.buck.rules.modern;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Preconditions;
import java.nio.file.Path;

public final class InputPath implements Comparable<InputPath> {
  private SourcePath sourcePath;

  public InputPath(SourcePath path) {
    if (path instanceof PathSourcePath) {
      ProjectFilesystem filesystem = ((PathSourcePath) path).getFilesystem();
      Path relativePath = ((PathSourcePath) path).getRelativePath();
      Preconditions.checkArgument(
          !filesystem.isIgnored(relativePath),
          "Ignored paths should not be used as inputs: %s",
          relativePath);
      Preconditions.checkArgument(
          !relativePath.startsWith(filesystem.getBuckPaths().getBuckOut()),
          "Paths in buck-out should not be used as PathSourcePath, use a "
              + "form of BuildTargetSourcePath instead:  %s",
          relativePath);
    }
    this.sourcePath = path;
  }

  /** This returns a SourcePath that can only be resolved by a LimitedSourcePathResolver. */
  public SourcePath getLimitedSourcePath() {
    return new LimitedSourcePath(sourcePath);
  }

  /**
   * Exposing sourcePath directly may lead to misuses. Only the internals of the ModernBuildRule
   * framework should access this.
   */
  SourcePath getSourcePath() {
    return sourcePath;
  }

  @Override
  public int compareTo(InputPath o) {
    return sourcePath.compareTo(o.sourcePath);
  }

  public static class Internals {
    public static SourcePath getSourcePathFrom(InputPath inputPath) {
      return inputPath.getSourcePath();
    }
  }
}
