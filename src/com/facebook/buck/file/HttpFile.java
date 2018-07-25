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
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MakeExecutableStep;
import com.facebook.buck.step.fs.MoveStep;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.net.URI;
import java.nio.file.Path;

/**
 * Represents a remote file that needs to be downloaded. Optionally, this class can be prevented
 * from running at build time, requiring a user to run {@code buck fetch} before executing the
 * build.
 */
public class HttpFile extends AbstractBuildRuleWithDeclaredAndExtraDeps {
  @AddToRuleKey(stringify = true)
  private final ImmutableList<URI> uris;

  @AddToRuleKey(stringify = true)
  private final HashCode sha256;

  @AddToRuleKey(stringify = true)
  private final Path output;

  @AddToRuleKey(stringify = true)
  private final boolean executable;

  private final Downloader downloader;

  public HttpFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Downloader downloader,
      ImmutableList<URI> uris,
      HashCode sha256,
      String out,
      boolean executable) {
    super(buildTarget, projectFilesystem, params);

    this.uris = uris;
    this.sha256 = sha256;
    this.downloader = downloader;
    this.executable = executable;

    output = BuildTargetPaths.getGenPath(getProjectFilesystem(), buildTarget, "%s/" + out);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path tempFile =
        BuildTargetPaths.getScratchPath(
            getProjectFilesystem(), getBuildTarget(), "%s/" + output.getFileName());

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), tempFile.getParent())));
    steps.add(
        new DownloadStep(
            getProjectFilesystem(),
            downloader,
            uris.get(0),
            uris.subList(1, uris.size()),
            FileHash.ofSha256(sha256),
            tempFile));
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));
    steps.add(new MoveStep(getProjectFilesystem(), tempFile, output));
    if (executable) {
      steps.add(new MakeExecutableStep(getProjectFilesystem(), output));
    }

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }
}
