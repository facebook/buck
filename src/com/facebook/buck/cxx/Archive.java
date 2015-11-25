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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.FileScrubberStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

/**
 * A {@link com.facebook.buck.rules.BuildRule} which builds an "ar" archive from input files
 * represented as {@link com.facebook.buck.rules.SourcePath}.
 */
public class Archive extends AbstractBuildRule {

  @AddToRuleKey
  private final Archiver archiver;
  @AddToRuleKey
  private final Tool ranlib;
  @AddToRuleKey(stringify = true)
  private final Path output;
  @AddToRuleKey
  private final ImmutableList<SourcePath> inputs;

  public Archive(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Archiver archiver,
      Tool ranlib,
      Path output,
      ImmutableList<SourcePath> inputs) {
    super(params, resolver);
    this.archiver = archiver;
    this.ranlib = ranlib;
    this.output = output;
    this.inputs = inputs;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {

    // Cache the archive we built.
    buildableContext.recordArtifact(output);

    return ImmutableList.of(
        new MkdirStep(getProjectFilesystem(), output.getParent()),
        new RmStep(getProjectFilesystem(), output, /* shouldForceDeletion */ true),
        new ArchiveStep(
            getProjectFilesystem().getRootPath(),
            archiver.getEnvironment(getResolver()),
            archiver.getCommandPrefix(getResolver()),
            output,
            getResolver().getAllAbsolutePaths(inputs)),
        new ShellStep(getProjectFilesystem().getRootPath()) {
          @Override
          protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
            return ImmutableList.<String>builder()
                .addAll(ranlib.getCommandPrefix(getResolver()))
                .add(output.toString())
                .build();
          }

          @Override
          public String getShortName() {
            return "ranlib";
          }
        },
        new FileScrubberStep(getProjectFilesystem(), output, archiver.getScrubbers()));
  }

  @Override
  public Path getPathToOutput() {
    return output;
  }

}
