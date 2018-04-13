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

import com.facebook.buck.file.downloader.Downloader;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MakeExecutableStep;
import com.facebook.buck.unarchive.UnzipStep;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Represents a remote file that needs to be downloaded. Optionally, this class can be prevented
 * from running at build time, requiring a user to run {@code buck fetch} before executing the
 * build.
 */
public class RemoteFile extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  @AddToRuleKey(stringify = true)
  private final URI uri;

  @AddToRuleKey(stringify = true)
  private final FileHash sha1;

  @AddToRuleKey(stringify = true)
  private final Path output;

  private final Downloader downloader;

  @AddToRuleKey(stringify = true)
  private final Type type;

  public RemoteFile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      Downloader downloader,
      URI uri,
      HashCode sha1,
      String out,
      Type type) {
    super(buildTarget, projectFilesystem, params);

    this.uri = uri;
    this.sha1 = FileHash.ofSha1(sha1);
    this.downloader = downloader;
    this.type = type;

    output = BuildTargets.getGenPath(getProjectFilesystem(), buildTarget, "%s/" + out);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Path tempFile =
        BuildTargets.getScratchPath(
            getProjectFilesystem(), getBuildTarget(), "%s/" + output.getFileName());

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), tempFile.getParent())));
    steps.add(
        new DownloadStep(
            getProjectFilesystem(), downloader, uri, ImmutableList.of(), sha1, tempFile));

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));
    if (type == Type.EXPLODED_ZIP) {

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), output)));
      steps.add(new UnzipStep(getProjectFilesystem(), tempFile, output, Optional.empty()));
    } else {
      steps.add(CopyStep.forFile(getProjectFilesystem(), tempFile, output));
    }
    if (type == Type.EXECUTABLE) {
      steps.add(new MakeExecutableStep(getProjectFilesystem(), output));
    }

    buildableContext.recordArtifact(output);

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    // EXPLODED_ZIP remote files can include many files; hashing the exploded files to compute
    // an input-based rule key can take a very long time. But we have an ace up our sleeve:
    // we already have a hash that represents the content in those exploded files!
    // Just pass that hash along so that RuleKeyBuilder can use it.
    return ExplicitBuildTargetSourcePath.builder()
        .setTarget(getBuildTarget())
        .setResolvedPath(output)
        .setPrecomputedHash(Optional.of(sha1.getHashCode()))
        .build();
  }

  /** Defines how the remote file should be treated when downloaded. */
  public enum Type {
    DATA,
    EXECUTABLE,
    EXPLODED_ZIP,
  }
}
