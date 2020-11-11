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

package com.facebook.buck.step.isolatedsteps.common;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.StringJoiner;

/** Copy IsolatedStep */
@BuckStyleValue
public abstract class CopyIsolatedStep extends IsolatedStep {

  abstract Path getSource();

  abstract Path getDestination();

  abstract CopySourceMode getCopySourceMode();

  public static CopyIsolatedStep of(Path source, Path destination, CopySourceMode copySourceMode) {
    return ImmutableCopyIsolatedStep.ofImpl(source, destination, copySourceMode);
  }

  /** Creates a CopyStep which copies a single file from 'source' to 'destination'. */
  public static CopyIsolatedStep forFile(Path source, Path destination) {
    return ImmutableCopyIsolatedStep.ofImpl(source, destination, CopySourceMode.FILE);
  }

  /** Creates a CopyStep which copies a single file from 'source' to 'destination'. */
  public static CopyIsolatedStep forFile(RelPath source, RelPath destination) {
    return forFile(source.getPath(), destination.getPath());
  }

  /** Creates a CopyStep which recursively copies a directory from 'source' to 'destination'. */
  public static CopyIsolatedStep forDirectory(
      Path source, Path destination, CopySourceMode copySourceMode) {
    return ImmutableCopyIsolatedStep.ofImpl(source, destination, copySourceMode);
  }

  /** Creates a CopyStep which recursively copies a directory from 'source' to 'destination'. */
  public static CopyIsolatedStep forDirectory(
      RelPath source, RelPath destination, CopySourceMode copySourceMode) {
    return forDirectory(source.getPath(), destination.getPath(), copySourceMode);
  }

  @Override
  public String getShortName() {
    return "cp";
  }

  @Override
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    StringJoiner args = new StringJoiner(" ");
    args.add("cp");
    switch (getCopySourceMode()) {
      case FILE:
        args.add(getSource().toString());
        break;

      case DIRECTORY_AND_CONTENTS:
        args.add("-R");
        args.add(getSource().toString());
        break;

      case DIRECTORY_CONTENTS_ONLY:
        args.add("-R");
        // BSD and GNU cp have different behaviors with -R:
        // http://jondavidjohn.com/blog/2012/09/linux-vs-osx-the-cp-command
        //
        // To work around this, we use "sourceDir/*" as the source to
        // copy in this mode.

        // N.B., on Windows, java.nio.AbstractPath does not resolve *
        // as a path, causing InvalidPathException. Since this is purely a
        // description, manually create the source argument.
        args.add(getSource() + File.separator + "*");
        break;
    }
    args.add(getDestination().toString());
    return args.toString();
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException {
    ProjectFilesystemUtils.copy(
        context.getRuleCellRoot(), getSource(), getDestination(), getCopySourceMode());
    return StepExecutionResults.SUCCESS;
  }
}
