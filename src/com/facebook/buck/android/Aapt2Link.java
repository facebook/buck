/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.zip.ZipScrubberStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Perform the "aapt2 link" step of building an Android app. */
public class Aapt2Link extends AbstractBuildRule {
  @AddToRuleKey private final boolean includesVectorDrawables;
  @AddToRuleKey private final boolean noAutoVersion;
  @AddToRuleKey private final boolean noVersionTransitions;
  @AddToRuleKey private final boolean noAutoAddOverlay;
  @AddToRuleKey private final boolean useProtoFormat;
  @AddToRuleKey private final ImmutableList<Aapt2Compile> compileRules;
  @AddToRuleKey private final SourcePath manifest;
  @AddToRuleKey private final ManifestEntries manifestEntries;
  @AddToRuleKey private final int packageIdOffset;
  @AddToRuleKey private final ImmutableList<SourcePath> dependencyResourceApks;

  private final AndroidPlatformTarget androidPlatformTarget;
  private final Supplier<ImmutableSortedSet<BuildRule>> buildDepsSupplier;

  private static final int BASE_PACKAGE_ID = 0x7f;

  Aapt2Link(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableList<Aapt2Compile> compileRules,
      ImmutableList<HasAndroidResourceDeps> resourceRules,
      SourcePath manifest,
      ManifestEntries manifestEntries,
      int packageIdOffset,
      ImmutableList<SourcePath> dependencyResourceApks,
      boolean includesVectorDrawables,
      boolean noAutoVersion,
      boolean noVersionTransitions,
      boolean noAutoAddOverlay,
      boolean useProtoFormat,
      AndroidPlatformTarget androidPlatformTarget) {
    super(buildTarget, projectFilesystem);
    this.androidPlatformTarget = androidPlatformTarget;
    this.compileRules = compileRules;
    this.manifest = manifest;
    this.manifestEntries = manifestEntries;
    this.packageIdOffset = packageIdOffset;
    this.dependencyResourceApks = dependencyResourceApks;
    this.includesVectorDrawables = includesVectorDrawables;
    this.noAutoVersion = noAutoVersion;
    this.noVersionTransitions = noVersionTransitions;
    this.noAutoAddOverlay = noAutoAddOverlay;
    this.useProtoFormat = useProtoFormat;
    this.buildDepsSupplier =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(compileRules)
                    .addAll(RichStream.from(resourceRules).filter(BuildRule.class).toOnceIterable())
                    .addAll(ruleFinder.filterBuildRuleInputs(manifest))
                    .addAll(
                        RichStream.from(dependencyResourceApks)
                            .map(ruleFinder::getRule)
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .collect(Collectors.toList()))
                    .build());
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDepsSupplier.get();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(),
                getProjectFilesystem(),
                getResourceApkPath().getParent())));

    AaptPackageResources.prepareManifestForAapt(
        context,
        steps,
        getProjectFilesystem(),
        getFinalManifestPath(),
        context.getSourcePathResolver().getAbsolutePath(manifest),
        manifestEntries);

    Path linkTreePath =
        BuildTargetPaths.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s/link-tree");

    // Need to reverse the order of the rules because aapt2 allows later resources
    // to override earlier ones, but aapt gives the earlier ones precedence.
    Iterable<Path> compiledResourcePaths =
        Lists.reverse(compileRules).stream()
                .map(Aapt2Compile::getSourcePathToOutput)
                .map(context.getSourcePathResolver()::getAbsolutePath)
            ::iterator;
    // Make a symlink tree to avoid lots of really long filenames
    // that can exceed the limit on Mac.
    int index = 1;
    ImmutableMap.Builder<Path, Path> symlinkMap = ImmutableMap.builder();
    ImmutableList.Builder<Path> symlinkPaths = ImmutableList.builder();
    for (Path flata : compiledResourcePaths) {
      Path linkPath = Paths.get(String.format("res-%09d.flata", index++));
      symlinkPaths.add(linkPath);
      symlinkMap.put(linkPath, flata);
    }

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), linkTreePath)));
    steps.add(
        new SymlinkTreeStep("aapt", getProjectFilesystem(), linkTreePath, symlinkMap.build()));

    steps.add(
        new Aapt2LinkStep(
            context.getSourcePathResolver(),
            getProjectFilesystem().resolve(linkTreePath),
            symlinkPaths.build(),
            dependencyResourceApks.stream()
                .map(context.getSourcePathResolver()::getRelativePath)
                .collect(Collectors.toList())));
    steps.add(ZipScrubberStep.of(getProjectFilesystem().resolve(getResourceApkPath())));

    buildableContext.recordArtifact(getFinalManifestPath());
    buildableContext.recordArtifact(getResourceApkPath());
    buildableContext.recordArtifact(getProguardConfigPath());
    buildableContext.recordArtifact(getRDotTxtPath());
    // Don't really need this, but it's small and might help with debugging.
    buildableContext.recordArtifact(getInitialRDotJavaDir());

    return steps.build();
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  private Path getFinalManifestPath() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/AndroidManifest.xml");
  }

  private Path getResourceApkPath() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/resource-apk.ap_");
  }

  private Path getProguardConfigPath() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/proguard-for-resources.pro");
  }

  private Path getRDotTxtPath() {
    return BuildTargetPaths.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/R.txt");
  }

  /** Directory containing R.java files produced by aapt2 link. */
  private Path getInitialRDotJavaDir() {
    return BuildTargetPaths.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/initial-rdotjava");
  }

  public AaptOutputInfo getAaptOutputInfo() {
    return AaptOutputInfo.builder()
        .setPathToRDotTxt(ExplicitBuildTargetSourcePath.of(getBuildTarget(), getRDotTxtPath()))
        .setPrimaryResourcesApkPath(
            ExplicitBuildTargetSourcePath.of(getBuildTarget(), getResourceApkPath()))
        .setAndroidManifestXml(
            ExplicitBuildTargetSourcePath.of(getBuildTarget(), getFinalManifestPath()))
        .setAaptGeneratedProguardConfigFile(
            ExplicitBuildTargetSourcePath.of(getBuildTarget(), getProguardConfigPath()))
        .build();
  }

  class Aapt2LinkStep extends ShellStep {
    private final List<Path> compiledResourcePaths;
    private final List<Path> compiledResourceApkPaths;
    private final SourcePathResolver pathResolver;

    Aapt2LinkStep(
        SourcePathResolver pathResolver,
        Path workingDirectory,
        List<Path> compiledResourcePaths,
        List<Path> compiledResourceApkPaths) {
      super(workingDirectory);
      this.pathResolver = pathResolver;
      this.compiledResourcePaths = compiledResourcePaths;
      this.compiledResourceApkPaths = compiledResourceApkPaths;
    }

    @Override
    public String getShortName() {
      return "aapt2_link";
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();
      builder.addAll(
          androidPlatformTarget.getAapt2Executable().get().getCommandPrefix(pathResolver));

      builder.add("link");
      if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
        builder.add("-v");
      }

      if (includesVectorDrawables) {
        builder.add("--no-version-vectors");
      }

      if (noAutoVersion) {
        builder.add("--no-auto-version");
      }

      if (noVersionTransitions) {
        builder.add("--no-version-transitions");
      }

      if (!noAutoAddOverlay) {
        builder.add("--auto-add-overlay");
      }

      if (useProtoFormat) {
        builder.add("--proto-format");
      }

      if (packageIdOffset != 0) {
        builder.add("--package-id", String.format("0x%x", BASE_PACKAGE_ID + packageIdOffset));
      }

      ProjectFilesystem pf = getProjectFilesystem();
      builder.add("-o", pf.resolve(getResourceApkPath()).toString());
      builder.add("--proguard", pf.resolve(getProguardConfigPath()).toString());
      builder.add("--manifest", pf.resolve(getFinalManifestPath()).toString());
      builder.add("-I", pf.resolve(androidPlatformTarget.getAndroidJar()).toString());
      for (Path resourceApk : compiledResourceApkPaths) {
        builder.add("-I", pf.resolve(resourceApk).toString());
      }
      // We don't need the R.java output, but aapt2 won't output R.txt
      // unless we also request R.java.
      builder.add("--java", pf.resolve(getInitialRDotJavaDir()).toString());
      builder.add("--output-text-symbols", pf.resolve(getRDotTxtPath()).toString());

      compiledResourcePaths.forEach(r -> builder.add("-R", r.toString()));

      return builder.build();
    }
  }
}
