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

package com.facebook.buck.step.fs;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.MorePaths;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.nio.file.Path;

public class SymlinkFileStep implements Step {

  private final Path existingFile;
  private final Path desiredLink;
  private final boolean useAbsolutePaths;

  public SymlinkFileStep(Path existingFile, Path desiredLink, boolean useAbsolutePaths) {
    this.existingFile = Preconditions.checkNotNull(existingFile);
    this.desiredLink = Preconditions.checkNotNull(desiredLink);
    this.useAbsolutePaths = useAbsolutePaths;
  }

  /**
   * Get the path to the existing file that should be linked.
   */
  private Path getExistingFilePath(ExecutionContext context) {
    // This could be either an absolute or relative path.
    // TODO(user): Ideally all symbolic links should be relative, consider eliminating the absolute
    // option.
    return (useAbsolutePaths ? getAbsolutePath(existingFile, context) :
        MorePaths.getRelativePath(existingFile, desiredLink.getParent()));
  }

  /**
   * Get the path to the desired link that should be created.
   */
  private Path getDesiredLinkPath(ExecutionContext context) {
    return getAbsolutePath(desiredLink, context);
  }

  private static Path getAbsolutePath(Path path, ExecutionContext context) {
    return context.getProjectFilesystem().getAbsolutifier().apply(path);
  }

  @Override
  public String getShortName() {
    return "symlink_file";
  }

  @Override
  public String getDescription (ExecutionContext context) {
    return Joiner.on(" ").join(
        "ln",
        "-f",
        "-s",
        getExistingFilePath(context),
        getDesiredLinkPath(context));
  }

  @Override
  public int execute(ExecutionContext context) {
    Path existingFilePath = getExistingFilePath(context);
    Path desiredLinkPath = getDesiredLinkPath(context);
    try {
      context.getProjectFilesystem().createSymLink(
          existingFilePath,
          desiredLinkPath,
          /* force */ true);
      return 0;
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
  }
}
