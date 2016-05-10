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

import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.MoreIterables;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import java.io.File;
import java.nio.file.Path;

public class HaskellCompileRule extends AbstractBuildRule {

  @AddToRuleKey
  private final Tool compiler;

  @AddToRuleKey
  private final ImmutableList<String> flags;

  @AddToRuleKey
  private final CxxSourceRuleFactory.PicType picType;

  @AddToRuleKey
  private final Optional<String> main;

  @AddToRuleKey
  private final ImmutableList<SourcePath> includes;

  @AddToRuleKey
  private final HaskellSources sources;

  public HaskellCompileRule(
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Tool compiler,
      ImmutableList<String> flags,
      CxxSourceRuleFactory.PicType picType,
      Optional<String> main,
      ImmutableList<SourcePath> includes,
      HaskellSources sources) {
    super(buildRuleParams, resolver);
    this.compiler = compiler;
    this.flags = flags;
    this.picType = picType;
    this.main = main;
    this.includes = includes;
    this.sources = sources;
  }

  private Path getObjectDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s")
        .resolve("objects");
  }

  private Path getInterfaceDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s")
        .resolve("interfaces");
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    buildableContext.recordArtifact(getObjectDir());
    buildableContext.recordArtifact(getInterfaceDir());
    return ImmutableList.of(
        new MakeCleanDirectoryStep(getProjectFilesystem(), getObjectDir()),
        new MakeCleanDirectoryStep(getProjectFilesystem(), getInterfaceDir()),
        new ShellStep(getProjectFilesystem().getRootPath()) {

          @Override
          public ImmutableMap<String, String> getEnvironmentVariables(ExecutionContext context) {
            return ImmutableMap.<String, String>builder()
                .putAll(super.getEnvironmentVariables(context))
                .putAll(compiler.getEnvironment(getResolver()))
                .build();
          }

          @Override
          protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
            return ImmutableList.<String>builder()
                .addAll(compiler.getCommandPrefix(getResolver()))
                .addAll(flags)
                .add("-c")
                .addAll(
                    picType == CxxSourceRuleFactory.PicType.PIC ?
                        ImmutableList.of("-dynamic", "-fPIC") :
                        ImmutableList.<String>of())
                .addAll(
                    MoreIterables.zipAndConcat(
                        Iterables.cycle("-main-is"),
                        main.asSet()))
                .add("-odir", getProjectFilesystem().resolve(getObjectDir()).toString())
                .add("-hidir", getProjectFilesystem().resolve(getInterfaceDir()).toString())
                .add("-i" + Joiner.on(':').join(
                    FluentIterable.from(includes)
                        .transform(getResolver().getAbsolutePathFunction())
                        .transform(Functions.toStringFunction())))
                .addAll(
                    FluentIterable.from(sources.getSourcePaths())
                        .transform(getResolver().getAbsolutePathFunction())
                        .transform(Functions.toStringFunction()))
                .build();
          }

          @Override
          public String getShortName() {
            return "haskell-compile";
          }

        });
  }

  @Override
  public boolean isCacheable() {
    // There's some non-detemrinism issues with GHC which currently prevent us from the caching
    // this rule.
    return false;
  }

  @Override
  public Path getPathToOutput() {
    return getInterfaceDir();
  }

  public ImmutableList<SourcePath> getObjects() {
    ImmutableList.Builder<SourcePath> objects = ImmutableList.builder();
    for (String module : sources.getModuleNames()) {
      objects.add(
          new BuildTargetSourcePath(
              getBuildTarget(),
              getObjectDir().resolve(module.replace('.', File.separatorChar) + ".o")));
    }
    return objects.build();
  }

  @VisibleForTesting
  protected ImmutableList<String> getFlags() {
    return flags;
  }

}
