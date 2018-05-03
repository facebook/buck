/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.file;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.unarchive.UnarchiveStep;
import com.facebook.buck.unarchive.UntarStep;
import com.facebook.buck.unarchive.UnzipStep;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Represents a remote file that needs to be downloaded. Optionally, this class can be prevented
 * from running at build time, requiring a user to run {@code buck fetch} before executing the
 * build.
 */
public class HttpArchive extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey(stringify = true)
  private final ArchiveFormat format;

  @AddToRuleKey(stringify = true)
  private final Optional<Path> stripPrefix;

  private final HttpFile implicitHttpFile;

  public HttpArchive(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      HttpFile implicitHttpFile,
      String out,
      ArchiveFormat format,
      Optional<Path> stripPrefix) {
    super(buildTarget, projectFilesystem, params);
    this.implicitHttpFile = implicitHttpFile;
    this.format = format;
    this.stripPrefix = stripPrefix;

    output = BuildTargets.getGenPath(getProjectFilesystem(), buildTarget, "%s/" + out);
  }

  @VisibleForTesting
  ArchiveFormat getFormat() {
    return format;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output)));

    steps.add(
        getUnarchiveStep(
            getProjectFilesystem(),
            context
                .getSourcePathResolver()
                .getAbsolutePath(implicitHttpFile.getSourcePathToOutput()),
            output,
            stripPrefix,
            format));

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }

  private UnarchiveStep getUnarchiveStep(
      ProjectFilesystem filesystem,
      Path archiveFile,
      Path destinationDirectory,
      Optional<Path> stripPrefix,
      ArchiveFormat format) {
    switch (format) {
      case TAR:
      case TAR_BZ2:
      case TAR_GZ:
      case TAR_XZ:
        return new UntarStep(filesystem, archiveFile, destinationDirectory, stripPrefix, format);
      case ZIP:
        return new UnzipStep(filesystem, archiveFile, destinationDirectory, stripPrefix);
    }
    throw new RuntimeException("Invalid format type " + format);
  }
}
