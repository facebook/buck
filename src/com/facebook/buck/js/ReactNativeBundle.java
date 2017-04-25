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

package com.facebook.buck.js;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Responsible for running the React Native JS packager in order to generate a single {@code .js}
 * bundle along with resources referenced by the javascript code.
 */
public class ReactNativeBundle extends AbstractBuildRule
    implements SupportsInputBasedRuleKey, SupportsDependencyFileRuleKey {

  public static final String JS_BUNDLE_OUTPUT_DIR_FORMAT = "__%s_js__/";
  public static final String RESOURCES_OUTPUT_DIR_FORMAT = "__%s_res__/";
  public static final String SOURCE_MAP_OUTPUT_FORMAT = "__%s_source_map__/source.map";

  @AddToRuleKey private final SourcePath entryPath;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> srcs;

  @AddToRuleKey private final boolean isUnbundle;

  @AddToRuleKey private final boolean isIndexedUnbundle;

  @AddToRuleKey private final boolean isDevMode;

  @AddToRuleKey private final boolean exposeSourceMap;

  @AddToRuleKey private final Tool jsPackager;

  @AddToRuleKey private final ReactNativePlatform platform;

  @AddToRuleKey private final String bundleName;

  @AddToRuleKey private final Optional<String> packagerFlags;

  private final Path jsOutputDir;
  private final Path resource;
  private final Path sourceMapOutputPath;

  protected ReactNativeBundle(
      BuildRuleParams ruleParams,
      SourcePath entryPath,
      ImmutableSortedSet<SourcePath> srcs,
      boolean isUnbundle,
      boolean isIndexedUnbundle,
      boolean isDevMode,
      boolean exposeSourceMap,
      String bundleName,
      Optional<String> packagerFlags,
      Tool jsPackager,
      ReactNativePlatform platform) {
    super(ruleParams);
    this.entryPath = entryPath;
    this.srcs = srcs;
    this.isUnbundle = isUnbundle;
    this.isIndexedUnbundle = isIndexedUnbundle;
    this.isDevMode = isDevMode;
    this.exposeSourceMap = exposeSourceMap;
    this.bundleName = bundleName;
    this.packagerFlags = packagerFlags;
    this.jsPackager = jsPackager;
    this.platform = platform;
    BuildTarget buildTarget = ruleParams.getBuildTarget();
    this.jsOutputDir = getPathToJSBundleDir(buildTarget, getProjectFilesystem());
    this.resource = getPathToResources(buildTarget, getProjectFilesystem());
    this.sourceMapOutputPath = getPathToSourceMap(buildTarget, getProjectFilesystem());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    // Generate the normal outputs.
    final Path jsOutput = jsOutputDir.resolve(bundleName);
    final Path depFile = getPathToDepFile(getBuildTarget(), getProjectFilesystem());

    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), jsOutput.getParent()));
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), resource));
    steps.addAll(
        MakeCleanDirectoryStep.of(getProjectFilesystem(), sourceMapOutputPath.getParent()));
    steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), depFile.getParent()));

    appendWorkerSteps(
        steps, context.getSourcePathResolver(), jsOutput, sourceMapOutputPath, depFile);

    buildableContext.recordArtifact(jsOutputDir);
    buildableContext.recordArtifact(resource);
    buildableContext.recordArtifact(sourceMapOutputPath.getParent());
    return steps.build();
  }

  private void appendWorkerSteps(
      ImmutableList.Builder<Step> stepBuilder,
      SourcePathResolver resolver,
      Path outputFile,
      Path sourceMapOutputPath,
      Path depFile) {

    // Setup the temp dir.
    final Path tmpDir =
        BuildTargets.getScratchPath(getProjectFilesystem(), getBuildTarget(), "%s__tmp");
    stepBuilder.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), tmpDir));

    // Run the bundler.
    ReactNativeBundleWorkerStep workerStep =
        new ReactNativeBundleWorkerStep(
            getProjectFilesystem(),
            tmpDir,
            jsPackager.getCommandPrefix(resolver),
            packagerFlags,
            platform,
            isUnbundle,
            isIndexedUnbundle,
            getProjectFilesystem().resolve(resolver.getAbsolutePath(entryPath)),
            isDevMode,
            getProjectFilesystem().resolve(outputFile),
            getProjectFilesystem().resolve(resource),
            getProjectFilesystem().resolve(sourceMapOutputPath));
    stepBuilder.add(workerStep);

    // Run the package to get the used inputs.
    ReactNativeDepsWorkerStep depsWorkerStep =
        new ReactNativeDepsWorkerStep(
            getProjectFilesystem(),
            tmpDir,
            jsPackager.getCommandPrefix(resolver),
            packagerFlags,
            platform,
            getProjectFilesystem().resolve(resolver.getAbsolutePath(entryPath)),
            getProjectFilesystem().resolve(depFile));
    stepBuilder.add(depsWorkerStep);
  }

  public SourcePath getJSBundleDir() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), jsOutputDir);
  }

  public Path getResources() {
    return resource;
  }

  public static Path getPathToJSBundleDir(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, JS_BUNDLE_OUTPUT_DIR_FORMAT);
  }

  public static Path getPathToResources(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, RESOURCES_OUTPUT_DIR_FORMAT);
  }

  public static Path getPathToSourceMap(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, SOURCE_MAP_OUTPUT_FORMAT);
  }

  public static Path getPathToDepFile(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(filesystem, target, "__%s_dep_file__/depfile.txt");
  }

  @Override
  public boolean useDependencyFileRuleKeys() {
    return true;
  }

  @Override
  public Predicate<SourcePath> getCoveredByDepFilePredicate() {
    // note, sorted set is intentionally converted to a hash set to achieve constant time look-up
    return ImmutableSet.copyOf(srcs)::contains;
  }

  @Override
  public Predicate<SourcePath> getExistenceOfInterestPredicate() {
    return (SourcePath path) -> false;
  }

  @Override
  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(BuildContext context)
      throws IOException {
    ImmutableList.Builder<SourcePath> inputs = ImmutableList.builder();

    // Use the generated depfile to determinate which sources ended up being used.
    ImmutableMap<Path, SourcePath> pathToSourceMap =
        Maps.uniqueIndex(srcs, context.getSourcePathResolver()::getAbsolutePath);
    Path depFile = getPathToDepFile(getBuildTarget(), getProjectFilesystem());
    for (String line : getProjectFilesystem().readLines(depFile)) {
      Path path = getProjectFilesystem().getPath(line);
      SourcePath sourcePath = pathToSourceMap.get(path);
      if (sourcePath == null) {
        throw new IOException(
            String.format(
                "%s: entry path '%s' transitively uses source file not preset in `srcs`: %s",
                getBuildTarget(), entryPath, path));
      }
      inputs.add(sourcePath);
    }

    return inputs.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(
        getBuildTarget(), exposeSourceMap ? sourceMapOutputPath : jsOutputDir);
  }
}
