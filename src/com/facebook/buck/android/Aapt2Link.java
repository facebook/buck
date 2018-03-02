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
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.coercer.ManifestEntries;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
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
import javax.annotation.Nullable;

/** Perform the "aapt2 link" step of building an Android app. */
public class Aapt2Link extends AbstractBuildRule {
  @AddToRuleKey private final boolean noAutoVersion;
  @AddToRuleKey private final ImmutableList<Aapt2Compile> compileRules;
  @AddToRuleKey private final SourcePath manifest;
  @AddToRuleKey private final ManifestEntries manifestEntries;

  private final AndroidPlatformTarget androidPlatformTarget;
  private final Supplier<ImmutableSortedSet<BuildRule>> buildDepsSupplier;

  Aapt2Link(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableList<Aapt2Compile> compileRules,
      ImmutableList<HasAndroidResourceDeps> resourceRules,
      SourcePath manifest,
      ManifestEntries manifestEntries,
      boolean noAutoVersion,
      AndroidPlatformTarget androidPlatformTarget) {
    super(buildTarget, projectFilesystem);
    this.androidPlatformTarget = androidPlatformTarget;
    this.compileRules = compileRules;
    this.manifest = manifest;
    this.manifestEntries = manifestEntries;
    this.noAutoVersion = noAutoVersion;
    this.buildDepsSupplier =
        MoreSuppliers.memoize(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(compileRules)
                    .addAll(RichStream.from(resourceRules).filter(BuildRule.class).toOnceIterable())
                    .addAll(ruleFinder.filterBuildRuleInputs(manifest))
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
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s/link-tree");

    // Need to reverse the order of the rules because aapt2 allows later resources
    // to override earlier ones, but aapt gives the earlier ones precedence.
    Iterable<Path> compiledResourcePaths =
        Lists.reverse(compileRules)
                .stream()
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
            getBuildTarget(), getProjectFilesystem().resolve(linkTreePath), symlinkPaths.build()));
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
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/AndroidManifest.xml");
  }

  private Path getResourceApkPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/resource-apk.ap_");
  }

  private Path getProguardConfigPath() {
    return BuildTargets.getGenPath(
        getProjectFilesystem(), getBuildTarget(), "%s/proguard-for-resources.pro");
  }

  private Path getRDotTxtPath() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/R.txt");
  }

  /** Directory containing R.java files produced by aapt2 link. */
  private Path getInitialRDotJavaDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s/initial-rdotjava");
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

    Aapt2LinkStep(
        BuildTarget buildTarget, Path workingDirectory, List<Path> compiledResourcePaths) {
      super(Optional.of(buildTarget), workingDirectory);
      this.compiledResourcePaths = compiledResourcePaths;
    }

    @Override
    public String getShortName() {
      return "aapt2_link";
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      ImmutableList.Builder<String> builder = ImmutableList.builder();

      builder.add(androidPlatformTarget.getAapt2Executable().toString());
      builder.add("link");
      if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
        builder.add("-v");
      }

      if (noAutoVersion) {
        builder.add("--no-auto-version");
      }
      builder.add("--auto-add-overlay");

      ProjectFilesystem pf = getProjectFilesystem();
      builder.add("-o", pf.resolve(getResourceApkPath()).toString());
      builder.add("--proguard", pf.resolve(getProguardConfigPath()).toString());
      builder.add("--manifest", pf.resolve(getFinalManifestPath()).toString());
      builder.add("-I", pf.resolve(androidPlatformTarget.getAndroidJar()).toString());
      // We don't need the R.java output, but aapt2 won't output R.txt
      // unless we also request R.java.
      builder.add("--java", pf.resolve(getInitialRDotJavaDir()).toString());
      builder.add("--output-text-symbols", pf.resolve(getRDotTxtPath()).toString());

      compiledResourcePaths.forEach(r -> builder.add("-R", r.toString()));

      return builder.build();
    }
  }
}
