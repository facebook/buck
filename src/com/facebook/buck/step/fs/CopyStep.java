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

package com.facebook.buck.step.fs;

import com.facebook.buck.core.build.execution.context.StepExecutionContext;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.facebook.buck.step.isolatedsteps.common.CopyIsolatedStep;
import com.google.common.annotations.VisibleForTesting;
import java.nio.file.Path;

/** Copy step that delegates to {@link CopyIsolatedStep} */
@BuckStyleValue
public abstract class CopyStep extends DelegateStep<CopyIsolatedStep> {

  /**
   * When copying a directory, this specifies whether only the contents of the directory should be
   * copied, or if the directory itself should be included.
   */
  public enum DirectoryMode {
    /**
     * Copy only the contents of the directory (recursively). If copying a source directory 'foo'
     * with a file 'bar.txt' to a destination directory 'dest', this will create 'dest/bar.txt'.
     */
    CONTENTS_ONLY,

    /**
     * Copy the directory and its contents (recursively). If copying a source directory 'foo' with a
     * file 'bar.txt' to a destination directory 'dest', this will create 'dest/foo/bar.txt'.
     */
    DIRECTORY_AND_CONTENTS
  }

  abstract Path getSource();

  abstract Path getDestination();

  abstract CopySourceMode getCopySourceMode();

  /** Creates a CopyStep which copies a single file from 'source' to 'destination'. */
  public static CopyStep forFile(Path source, Path destination) {
    return ImmutableCopyStep.ofImpl(source, destination, CopySourceMode.FILE);
  }

  /** Creates a CopyStep which copies a single file from 'source' to 'destination'. */
  public static CopyStep forFile(RelPath source, RelPath destination) {
    return forFile(source.getPath(), destination.getPath());
  }

  /**
   * Creates a CopyStep which recursively copies a directory from 'source' to 'destination'. See
   * {@link DirectoryMode} for options to control the copy.
   */
  public static CopyStep forDirectory(Path source, Path destination, DirectoryMode directoryMode) {
    return ImmutableCopyStep.ofImpl(source, destination, getCopySourceMode(directoryMode));
  }

  private static CopySourceMode getCopySourceMode(DirectoryMode directoryMode) {
    switch (directoryMode) {
      case CONTENTS_ONLY:
        return CopySourceMode.DIRECTORY_CONTENTS_ONLY;

      case DIRECTORY_AND_CONTENTS:
        return CopySourceMode.DIRECTORY_AND_CONTENTS;

      default:
        throw new IllegalArgumentException("Invalid directory mode: " + directoryMode);
    }
  }

  /**
   * Creates a CopyStep which recursively copies a directory from 'source' to 'destination'. See
   * {@link DirectoryMode} for options to control the copy.
   */
  public static CopyStep forDirectory(
      RelPath source, RelPath destination, DirectoryMode directoryMode) {
    return forDirectory(source.getPath(), destination.getPath(), directoryMode);
  }

  @Override
  protected CopyIsolatedStep createDelegate(StepExecutionContext context) {
    return CopyIsolatedStep.of(getSource(), getDestination(), getCopySourceMode());
  }

  @Override
  protected String getShortNameSuffix() {
    return "cp";
  }

  @VisibleForTesting
  boolean isRecursive() {
    return getCopySourceMode() != CopySourceMode.FILE;
  }
}
