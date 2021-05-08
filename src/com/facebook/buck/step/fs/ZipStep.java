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
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.file.PathMatcher;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.isolatedsteps.common.ZipIsolatedStep;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;

/** A {@link com.facebook.buck.step.Step} that creates a ZIP archive.. */
@SuppressWarnings("PMD.AvoidUsingOctalValues")
@BuckStyleValue
public abstract class ZipStep extends DelegateStep<ZipIsolatedStep> {

  abstract AbsPath getRootPath();

  abstract Path getPathToZipFile();

  abstract ImmutableSet<PathMatcher> getIgnoredPaths();

  abstract ImmutableSet<Path> getPaths();

  abstract boolean getJunkPaths();

  abstract ZipCompressionLevel getZipCompressionLevel();

  abstract Path getBaseDir();

  /**
   * Create a {@link ZipStep} to create or update a zip archive.
   *
   * <p>Note that paths added to the archive are always relative to the working directory.<br>
   * For example, if you're in {@code /dir} and you add {@code file.txt}, you get an archive
   * containing just the file. If you were in {@code /} and added {@code dir/file.txt}, you would
   * get an archive containing the file within a directory.
   */
  @Override
  protected String getShortNameSuffix() {
    return "zip";
  }

  @Override
  protected ZipIsolatedStep createDelegate(StepExecutionContext context) {
    return ZipIsolatedStep.of(
        getRootPath(),
        getPathToZipFile(),
        getIgnoredPaths(),
        getPaths(),
        getJunkPaths(),
        getZipCompressionLevel(),
        getBaseDir());
  }

  public static ZipStep of(
      ProjectFilesystem filesystem,
      Path pathToZipFile,
      ImmutableSet<Path> paths,
      boolean junkPaths,
      ZipCompressionLevel compressionLevel,
      Path baseDir) {
    return ImmutableZipStep.ofImpl(
        filesystem.getRootPath(),
        pathToZipFile,
        filesystem.getIgnoredPaths(),
        paths,
        junkPaths,
        compressionLevel,
        baseDir);
  }
}
