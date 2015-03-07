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

import com.facebook.buck.io.ProjectFilesystem.CopySourceMode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Path;

public class CopyStep implements Step {

  /**
   * When copying a directory, this specifies whether only
   * the contents of the directory should be copied, or
   * if the directory itself should be included.
   */
  public enum DirectoryMode {
      /**
       * Copy only the contents of the directory (recursively). If
       * copying a source directory 'foo' with a file 'bar.txt' to a
       * destination directory 'dest', this will create
       * 'dest/bar.txt'.
       */
      CONTENTS_ONLY,

      /**
       * Copy the directory and its contents (recursively). If copying
       * a source directory 'foo' with a file 'bar.txt' to a
       * destination directory 'dest', this will create
       * 'dest/foo/bar.txt'.
       */
      DIRECTORY_AND_CONTENTS
  }

  private final Path source;
  private final Path destination;
  private final CopySourceMode copySourceMode;

  private CopyStep(Path source, Path destination, CopySourceMode copySourceMode) {
    this.source = source;
    this.destination = destination;
    this.copySourceMode = copySourceMode;
  }

  /**
   * Creates a CopyStep which copies a single file from 'source' to
   * 'destination'.
   */
  public static CopyStep forFile(Path source, Path destination) {
    return new CopyStep(source, destination, CopySourceMode.FILE);
  }

  /**
   * Creates a CopyStep which recursively copies a directory from
   * 'source' to 'destination'. See {@link DirectoryMode} for options
   * to control the copy.
   */
  public static CopyStep forDirectory(
      Path source,
      Path destination,
      DirectoryMode directoryMode) {
    CopySourceMode copySourceMode;
    switch (directoryMode) {
      case CONTENTS_ONLY:
        copySourceMode = CopySourceMode.DIRECTORY_CONTENTS_ONLY;
        break;
      case DIRECTORY_AND_CONTENTS:
        copySourceMode = CopySourceMode.DIRECTORY_AND_CONTENTS;
        break;
      default:
        throw new IllegalArgumentException("Invalid directory mode: " + directoryMode);
    }
    return new CopyStep(source, destination, copySourceMode);
  }

  @Override
  public String getShortName() {
    return "cp";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("cp");
    switch (copySourceMode) {
      case FILE:
        args.add(source.toString());
        break;
      case DIRECTORY_AND_CONTENTS:
        args.add("-R");
        args.add(source.toString());
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
        args.add(source.toString() + "/*");
        break;
    }
    args.add(destination.toString());
    return Joiner.on(" ").join(args.build());
  }

  @VisibleForTesting
  Path getSource() {
    return source;
  }

  @VisibleForTesting
  Path getDestination() {
    return destination;
  }

  public boolean isRecursive() {
    return copySourceMode != CopySourceMode.FILE;
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      context.getProjectFilesystem().copy(source, destination, copySourceMode);
      return 0;
    } catch (IOException e) {
      context.logError(e, "Failed when trying to copy: %s", getDescription(context));
      return 1;
    }
  }
}
