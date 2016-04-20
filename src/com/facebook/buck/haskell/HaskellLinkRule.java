/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.haskell;

import com.facebook.buck.cxx.Archive;
import com.facebook.buck.cxx.ArchiveStep;
import com.facebook.buck.cxx.RanlibStep;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.TouchStep;
import com.facebook.buck.util.MoreIterables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

public class HaskellLinkRule extends AbstractBuildRule {

  @AddToRuleKey
  private final Tool archiver;

  @AddToRuleKey
  private final Tool ranlib;

  @AddToRuleKey
  private final Tool linker;

  @AddToRuleKey
  private final ImmutableList<String> flags;

  @AddToRuleKey
  private final String name;

  @AddToRuleKey
  private final ImmutableList<Arg> linkerArgs;

  private final boolean cacheable;

  public HaskellLinkRule(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Tool archiver,
      Tool ranlib,
      Tool linker,
      ImmutableList<String> flags,
      String name,
      ImmutableList<Arg> linkerArgs,
      boolean cacheable) {
    super(buildRuleParams, resolver);
    this.archiver = archiver;
    this.ranlib = ranlib;
    this.linker = linker;
    this.flags = flags;
    this.name = name;
    this.linkerArgs = linkerArgs;
    this.cacheable = cacheable;
  }

  protected static Path getOutputDir(BuildTarget target) {
    return BuildTargets.getGenPath(target, "%s");
  }

  private Path getOutput() {
    return getOutputDir(getBuildTarget()).resolve(name);
  }

  private Path getScratchDir() {
    return BuildTargets.getScratchPath(getBuildTarget(), "%s");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(getOutput());
    final Path emptyInput = getScratchDir().resolve("empty");
    final Path emptyArchive = getScratchDir().resolve("libempty.a");
    return ImmutableList.of(
        new MakeCleanDirectoryStep(getProjectFilesystem(), getScratchDir()),
        new MakeCleanDirectoryStep(getProjectFilesystem(), getOutputDir(getBuildTarget())),
        // Since we use `-optl` to pass all linker inputs directly to the linker, the haskell linker
        // will complain about not having any input files.  So, create a dummy archive with an empty
        // file and pass that in normally to work around this.
        new TouchStep(getProjectFilesystem(), emptyInput),
        new ArchiveStep(
            getProjectFilesystem(),
            archiver.getEnvironment(getResolver()),
            archiver.getCommandPrefix(getResolver()),
            Archive.Contents.NORMAL,
            emptyArchive,
            ImmutableList.of(emptyInput)),
        new RanlibStep(
            getProjectFilesystem(),
            ranlib.getEnvironment(getResolver()),
            ranlib.getCommandPrefix(getResolver()),
            emptyArchive),
        new ShellStep(getProjectFilesystem().getRootPath()) {

          @Override
          public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
            return ImmutableMap.<String, String>builder()
                .putAll(super.getEnvironmentVariables(context))
                .putAll(linker.getEnvironment(getResolver()))
                .build();
          }

          @Override
          protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
            return ImmutableList.<String>builder()
                .addAll(linker.getCommandPrefix(getResolver()))
                .addAll(flags)
                .add("-o", getProjectFilesystem().resolve(getOutput()).toString())
                .add(getProjectFilesystem().resolve(emptyArchive).toString())
                .addAll(
                    MoreIterables.zipAndConcat(
                        Iterables.cycle("-optl"),
                        Arg.stringify(linkerArgs)))
                .build();
          }

          @Override
          public String getShortName() {
            return "haskell-link";
          }

        });
  }

  @Override
  public Path getPathToOutput() {
    return getOutput();
  }

  @Override
  public boolean isCacheable() {
    return cacheable;
  }

}
