/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.features.js;

import com.facebook.buck.android.packageable.AndroidPackageable;
import com.facebook.buck.android.packageable.AndroidPackageableCollector;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.android.toolchain.AndroidSdkLocation;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.sandbox.SandboxExecutionStrategy;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class JsBundleGenrule extends Genrule
    implements AndroidPackageable, HasRuntimeDeps, JsBundleOutputs, JsDependenciesOutputs {

  @AddToRuleKey final SourcePath jsBundleSourcePath;
  @AddToRuleKey final boolean rewriteSourcemap;
  @AddToRuleKey final boolean rewriteMisc;
  @AddToRuleKey final boolean rewriteDepsFile;
  @AddToRuleKey final boolean skipResources;
  @AddToRuleKey private final String bundleName;
  private final JsBundleOutputs jsBundle;
  private final JsDependenciesOutputs jsDependencies;

  public JsBundleGenrule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleResolver resolver,
      BuildRuleParams params,
      SandboxExecutionStrategy sandboxExecutionStrategy,
      JsBundleGenruleDescriptionArg args,
      Optional<Arg> cmd,
      Optional<Arg> bash,
      Optional<Arg> cmdExe,
      Optional<String> environmentExpansionSeparator,
      Optional<AndroidPlatformTarget> androidPlatformTarget,
      Optional<AndroidNdk> androidNdk,
      Optional<AndroidSdkLocation> androidSdkLocation,
      JsBundleOutputs jsBundle,
      JsDependenciesOutputs jsDependencies,
      String bundleName) {
    super(
        buildTarget,
        projectFilesystem,
        resolver,
        params,
        sandboxExecutionStrategy,
        args.getSrcs(),
        cmd,
        bash,
        cmdExe,
        args.getType(),
        JsBundleOutputs.JS_DIR_NAME,
        false,
        true,
        environmentExpansionSeparator,
        androidPlatformTarget,
        androidNdk,
        androidSdkLocation,
        false);
    this.jsBundle = jsBundle;
    this.jsDependencies = jsDependencies;
    this.jsBundleSourcePath = jsBundle.getSourcePathToOutput();
    this.rewriteSourcemap = args.getRewriteSourcemap();
    this.rewriteMisc = args.getRewriteMisc();
    this.rewriteDepsFile = args.getRewriteDepsFile();
    this.skipResources = args.getSkipResources();
    this.bundleName = bundleName;
  }

  @Override
  protected void addEnvironmentVariables(
      SourcePathResolver pathResolver,
      ImmutableMap.Builder<String, String> environmentVariablesBuilder) {
    super.addEnvironmentVariables(pathResolver, environmentVariablesBuilder);
    environmentVariablesBuilder
        .put("JS_DIR", pathResolver.getAbsolutePath(jsBundle.getSourcePathToOutput()).toString())
        .put("JS_BUNDLE_NAME", jsBundle.getBundleName())
        .put("JS_BUNDLE_NAME_OUT", bundleName)
        .put("MISC_DIR", pathResolver.getAbsolutePath(jsBundle.getSourcePathToMisc()).toString())
        .put(
            "PLATFORM",
            JsFlavors.PLATFORM_DOMAIN
                .getFlavor(getBuildTarget().getFlavors())
                .map(flavor -> flavor.getName())
                .orElse(""))
        .put("RELEASE", getBuildTarget().getFlavors().contains(JsFlavors.RELEASE) ? "1" : "")
        .put(
            "RES_DIR", pathResolver.getAbsolutePath(jsBundle.getSourcePathToResources()).toString())
        .put(
            "SOURCEMAP",
            pathResolver.getAbsolutePath(jsBundle.getSourcePathToSourceMap()).toString())
        .put(
            "DEPENDENCIES",
            pathResolver.getAbsolutePath(jsDependencies.getSourcePathToDepsFile()).toString());

    if (rewriteSourcemap) {
      environmentVariablesBuilder.put(
          "SOURCEMAP_OUT", pathResolver.getAbsolutePath(getSourcePathToSourceMap()).toString());
    }
    if (rewriteMisc) {
      environmentVariablesBuilder.put(
          "MISC_OUT", pathResolver.getAbsolutePath(getSourcePathToMisc()).toString());
    }
    if (rewriteDepsFile) {
      environmentVariablesBuilder.put(
          "DEPENDENCIES_OUT", pathResolver.getAbsolutePath(getSourcePathToDepsFile()).toString());
    }
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
    ImmutableList<Step> buildSteps = super.getBuildSteps(context, buildableContext);
    OptionalInt lastRmStep =
        IntStream.range(0, buildSteps.size())
            .map(x -> buildSteps.size() - 1 - x)
            .filter(i -> buildSteps.get(i) instanceof RmStep)
            .findFirst();

    Preconditions.checkState(
        lastRmStep.isPresent(), "Genrule is expected to have at least on RmDir step");

    ImmutableList.Builder<Step> builder =
        ImmutableList.<Step>builder()
            // First, all Genrule steps including the last RmDir step are added
            .addAll(buildSteps.subList(0, lastRmStep.getAsInt() + 1))
            // Our MkdirStep must run after all RmSteps created by Genrule to prevent immediate
            // deletion of the directory. It must, however, run before the genrule command itself
            // runs.
            .add(
                MkdirStep.of(
                    BuildCellRelativePath.fromCellRelativePath(
                        context.getBuildCellRootPath(),
                        getProjectFilesystem(),
                        sourcePathResolver.getRelativePath(getSourcePathToOutput()))));

    if (rewriteSourcemap) {
      // If the genrule rewrites the source map, we have to create the parent dir, and record
      // the build artifact

      SourcePath sourcePathToSourceMap = getSourcePathToSourceMap();
      buildableContext.recordArtifact(sourcePathResolver.getRelativePath(sourcePathToSourceMap));
      builder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  sourcePathResolver.getRelativePath(sourcePathToSourceMap).getParent())));
    }

    if (rewriteMisc) {
      // If the genrule rewrites the misc folder, we have to create the corresponding dir, and
      // record its contents

      SourcePath miscDirPath = getSourcePathToMisc();
      buildableContext.recordArtifact(sourcePathResolver.getRelativePath(miscDirPath));
      builder.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  sourcePathResolver.getRelativePath(miscDirPath))));
    }

    if (rewriteDepsFile) {
      // If the genrule rewrites the dependencies file, we have to record its contents

      SourcePath dependenciesFilePath = getSourcePathToDepsFile();
      buildableContext.recordArtifact(sourcePathResolver.getRelativePath(dependenciesFilePath));
    }

    // Last, we add all remaining genrule commands after the last RmStep
    return builder.addAll(buildSteps.subList(lastRmStep.getAsInt() + 1, buildSteps.size())).build();
  }

  @Override
  public SourcePath getSourcePathToSourceMap() {
    return rewriteSourcemap
        ? JsBundleOutputs.super.getSourcePathToSourceMap()
        : jsBundle.getSourcePathToSourceMap();
  }

  @Override
  public SourcePath getSourcePathToResources() {
    return jsBundle.getSourcePathToResources();
  }

  @Override
  public SourcePath getSourcePathToMisc() {
    return rewriteMisc
        ? JsBundleOutputs.super.getSourcePathToMisc()
        : jsBundle.getSourcePathToMisc();
  }

  @Override
  public SourcePath getSourcePathToDepsFile() {
    return rewriteDepsFile
        ? JsDependenciesOutputs.super.getSourcePathToDepsFile()
        : jsDependencies.getSourcePathToDepsFile();
  }

  @Override
  public Iterable<AndroidPackageable> getRequiredPackageables(BuildRuleResolver ruleResolver) {
    return !this.skipResources && jsBundle instanceof AndroidPackageable
        ? ((AndroidPackageable) jsBundle).getRequiredPackageables(ruleResolver)
        : ImmutableList.of();
  }

  @Override
  public void addToCollector(AndroidPackageableCollector collector) {
    collector.addAssetsDirectory(getBuildTarget(), getSourcePathToOutput());
  }

  @Override
  public String getBundleName() {
    return bundleName;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return Stream.of(jsBundle.getBuildTarget());
  }

  @Override
  public JsDependenciesOutputs getJsDependenciesOutputs(ActionGraphBuilder graphBuilder) {
    return this;
  }
}
