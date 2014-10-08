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

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;

import java.net.URI;
import java.nio.file.Path;

/**
 * Represents a remote file that needs to be downloaded. Optionally, this class can be prevented
 * from running at build time, requiring a user to run {@code buck fetch} before executing the
 * build.
 */
public class RemoteFile extends AbstractBuildRule {
  private final boolean isBuildTimeDownloadingOk;
  private final URI uri;
  private final HashCode sha1;
  private final Path output;
  private final Downloader downloader;

  public RemoteFile(
      BuildRuleParams params,
      SourcePathResolver resolver,
      boolean isBuildTimeDownloadingOk,
      Downloader downloader,
      URI uri,
      HashCode sha1,
      String out) {
    super(params, resolver);
    this.isBuildTimeDownloadingOk = isBuildTimeDownloadingOk;
    this.uri = Preconditions.checkNotNull(uri);
    this.sha1 = Preconditions.checkNotNull(sha1);
    this.downloader = Preconditions.checkNotNull(downloader);

    output = BuildTargets.getGenPath(
        params.getBuildTarget(),
        String.format("%%s/%s", Preconditions.checkNotNull(out)));
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    return ImmutableSet.of();
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("donwnload-at-runtime", isBuildTimeDownloadingOk)
        .setReflectively("sha1", sha1.toString())
        .setReflectively("out", output.toString())
        .setReflectively("url", uri.toString());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    if (!isBuildTimeDownloadingOk) {
      throw new HumanReadableException(
          "Downloading files at runtime is disabled, please run 'buck fetch' before your build");
    }

    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    Path tempFile = BuildTargets.getBinPath(
        getBuildTarget(), String.format("%%s/%s", output.getFileName()));

    steps.add(new MakeCleanDirectoryStep(tempFile.getParent()));
    steps.add(new DownloadStep(downloader, uri, sha1, tempFile));

    steps.add(new MakeCleanDirectoryStep(output.getParent()));
    steps.add(CopyStep.forFile(tempFile, output));

    return steps.build();
  }

  @Override
  public Path getPathToOutputFile() {
    return output;
  }
}
