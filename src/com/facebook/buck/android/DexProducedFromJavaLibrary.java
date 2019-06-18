/*
 * Copyright 2013-present Facebook, Inc.
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

import static com.facebook.buck.android.DexProducedFromJavaLibrary.MetadataResource.CLASSNAMES_TO_HASHES;
import static com.facebook.buck.android.DexProducedFromJavaLibrary.MetadataResource.REFERENCED_RESOURCES;
import static com.facebook.buck.android.DexProducedFromJavaLibrary.MetadataResource.WEIGHT_ESTIMATE;

import com.facebook.buck.android.DxStep.Option;
import com.facebook.buck.android.dalvik.EstimateDexWeightStep;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.zip.ZipScrubberStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * {@link DexProducedFromJavaLibrary} is a {@link BuildRule} that serves a very specific purpose: it
 * takes a {@link JavaLibrary} and dexes the output of the {@link JavaLibrary} if its list of
 * classes is non-empty. Because it is expected to be used with pre-dexing, we always pass the
 * {@code --force-jumbo} flag to {@code dx} in this buildable.
 *
 * <p>Most {@link BuildRule}s can determine the (possibly null) path to their output file from their
 * definition. This is an anomaly because we do not know whether this will write a {@code .dex} file
 * until runtime. Unfortunately, because there is no such thing as an empty {@code .dex} file, we
 * cannot write a meaningful "dummy .dex" if there are no class files to pass to {@code dx}.
 */
public class DexProducedFromJavaLibrary extends AbstractBuildRule
    implements SupportsInputBasedRuleKey,
        InitializableFromDisk<DexProducedFromJavaLibrary.BuildOutput> {

  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;
  private final BuildableSupport.DepsSupplier buildDepsSupplier;
  @AddToRuleKey private final Impl buildable;

  public DexProducedFromJavaLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      AndroidPlatformTarget androidPlatformTarget,
      JavaLibrary javaLibrary,
      String dexTool,
      int weightFactor,
      ImmutableSortedSet<BuildRule> desugarDeps) {
    super(buildTarget, projectFilesystem);
    this.buildable =
        new Impl(
            buildTarget,
            dexTool,
            weightFactor,
            getDesugarClassPaths(desugarDeps),
            androidPlatformTarget,
            javaLibrary);
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
    this.buildDepsSupplier = BuildableSupport.buildDepsSupplier(this, ruleFinder);
  }

  public DexProducedFromJavaLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      AndroidPlatformTarget androidPlatformTarget,
      JavaLibrary javaLibrary) {
    this(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        androidPlatformTarget,
        javaLibrary,
        DxStep.DX,
        1,
        ImmutableSortedSet.of());
  }

  /** Impl class */
  static class Impl implements AddsToRuleKey {

    private static final String DEX_RULE_METADATA = "metadata";

    @AddToRuleKey private final String dexTool;
    // Scale factor to apply to our weight estimate, for deceptive dexes.
    @AddToRuleKey private final int weightFactor;
    @AddToRuleKey private final ImmutableSortedSet<SourcePath> desugarDeps;
    @AddToRuleKey private final SourcePath javaLibrarySourcePath;

    private final AndroidPlatformTarget androidPlatformTarget;
    private final boolean desugarEnabled;

    private final BuildTarget buildTarget;
    private final JavaLibrary javaLibrary;

    Impl(
        BuildTarget buildTarget,
        String dexTool,
        int weightFactor,
        ImmutableSortedSet<SourcePath> desugarDeps,
        AndroidPlatformTarget androidPlatformTarget,
        JavaLibrary javaLibrary) {
      this.dexTool = dexTool;
      this.weightFactor = weightFactor;
      this.desugarDeps = desugarDeps;
      this.androidPlatformTarget = androidPlatformTarget;
      this.desugarEnabled = javaLibrary.isDesugarEnabled();
      this.javaLibrarySourcePath = javaLibrary.getSourcePathToOutput();
      this.buildTarget = buildTarget;
      this.javaLibrary = javaLibrary;
    }

    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext, ProjectFilesystem filesystem) {
      SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
      ImmutableList.Builder<Step> steps = ImmutableList.builder();
      addPrepareDirSteps(filesystem, context, steps);

      // If there are classes, run dx.
      ImmutableSortedMap<String, HashCode> classNamesToHashes = getClassNamesToHashes();
      boolean hasClassesToDx = !classNamesToHashes.isEmpty();
      Supplier<Integer> weightEstimate;

      @Nullable DxStep dx;

      if (hasClassesToDx) {
        Path pathToOutputFile = sourcePathResolver.getAbsolutePath(javaLibrarySourcePath);
        EstimateDexWeightStep estimate = new EstimateDexWeightStep(filesystem, pathToOutputFile);
        steps.add(estimate);
        weightEstimate = estimate;

        // To be conservative, use --force-jumbo for these intermediate .dex files so that they can
        // be merged into a final classes.dex that uses jumbo instructions.
        EnumSet<DxStep.Option> options =
            EnumSet.of(
                DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
                DxStep.Option.RUN_IN_PROCESS,
                DxStep.Option.NO_OPTIMIZE,
                DxStep.Option.FORCE_JUMBO);
        if (!desugarEnabled) {
          options.add(Option.NO_DESUGAR);
        }
        Path pathToDex = getPathToDex(filesystem);
        dx =
            new DxStep(
                filesystem,
                androidPlatformTarget,
                pathToDex,
                Collections.singleton(pathToOutputFile),
                options,
                Optional.empty(),
                dexTool,
                dexTool.equals(DxStep.D8),
                getAbsolutePaths(desugarDeps, sourcePathResolver),
                Optional.empty());
        steps.add(dx);

        // The `DxStep` delegates to android tools to build a ZIP with timestamps in it, making
        // the output non-deterministic.  So use an additional scrubbing step to zero these out.
        steps.add(ZipScrubberStep.of(filesystem.resolve(pathToDex)));

      } else {
        dx = null;
        weightEstimate = Suppliers.ofInstance(0);
      }

      // Run a step to record artifacts and metadata. The values recorded depend upon whether dx was
      // run.
      String stepName = hasClassesToDx ? "record_dx_success" : "record_empty_dx";
      AbstractExecutionStep recordArtifactAndMetadataStep =
          new AbstractExecutionStep(stepName) {

            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              if (hasClassesToDx) {
                buildableContext.recordArtifact(getPathToDex(filesystem));

                @Nullable
                Collection<String> referencedResources = dx.getResourcesReferencedInCode();
                if (referencedResources != null) {
                  writeMetadataValue(
                      buildableContext,
                      REFERENCED_RESOURCES,
                      ObjectMappers.WRITER.writeValueAsString(
                          Ordering.natural().immutableSortedCopy(referencedResources)));
                }
              }

              writeMetadataValue(
                  buildableContext,
                  WEIGHT_ESTIMATE,
                  String.valueOf(weightFactor * weightEstimate.get()));

              // Record the classnames to hashes map.
              writeMetadataValue(
                  buildableContext,
                  CLASSNAMES_TO_HASHES,
                  ObjectMappers.WRITER.writeValueAsString(
                      Maps.transformValues(classNamesToHashes, Object::toString)));

              return StepExecutionResults.SUCCESS;
            }

            private void writeMetadataValue(
                BuildableContext buildableContext, String key, String value) throws IOException {
              Path path = getMetadataPath(filesystem, key);
              filesystem.mkdirs(path.getParent());
              filesystem.writeContentsToPath(value, path);
              buildableContext.recordArtifact(path);
            }
          };
      steps.add(recordArtifactAndMetadataStep);

      return steps.build();
    }

    private ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
      return javaLibrary.getClassNamesToHashes();
    }

    private void addPrepareDirSteps(
        ProjectFilesystem filesystem, BuildContext context, ImmutableList.Builder<Step> steps) {
      steps.add(
          RmStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), filesystem, getPathToDex(filesystem))));

      // Make sure that the buck-out/gen/ directory exists for this.buildTarget.
      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  filesystem,
                  getPathToDex(filesystem).getParent())));
    }

    private Collection<Path> getAbsolutePaths(
        Collection<SourcePath> sourcePaths, SourcePathResolver sourcePathResolver) {
      return sourcePaths.stream()
          .filter(Objects::nonNull)
          .map(sourcePathResolver::getAbsolutePath)
          .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
    }

    private Path getPathToDex(ProjectFilesystem filesystem) {
      return BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s.dex.jar");
    }

    private Path getMetadataPath(ProjectFilesystem filesystem, String key) {
      return BuildTargetPaths.getGenPath(
          filesystem, buildTarget, "%s/" + DEX_RULE_METADATA + "/" + key);
    }
  }

  /** Metadata Resource constants */
  static class MetadataResource {

    static final String WEIGHT_ESTIMATE = "weight_estimate";
    static final String CLASSNAMES_TO_HASHES = "classnames_to_hashes";
    static final String REFERENCED_RESOURCES = "referenced_resources";
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    return getBuildable().getBuildSteps(context, buildableContext, getProjectFilesystem());
  }

  private ImmutableSortedSet<SourcePath> getDesugarClassPaths(Collection<BuildRule> desugarDeps) {
    if (desugarDeps == null) {
      return ImmutableSortedSet.of();
    }
    return desugarDeps.stream()
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
  }

  @Override
  public BuildOutput initializeFromDisk(SourcePathResolver pathResolver) throws IOException {
    return new BuildOutput(
        readWeightEstimateFromMetadata(),
        readClassNamesToHashesFromMetadata(),
        readReferencedResourcesFromMetadata());
  }

  private int readWeightEstimateFromMetadata() {
    return Integer.parseInt(readMetadataValue(WEIGHT_ESTIMATE).get());
  }

  private ImmutableSortedMap<String, HashCode> readClassNamesToHashesFromMetadata()
      throws IOException {
    Map<String, String> map =
        ObjectMappers.readValue(
            readMetadataValue(CLASSNAMES_TO_HASHES).get(),
            new TypeReference<Map<String, String>>() {});
    return ImmutableSortedMap.copyOf(Maps.transformValues(map, HashCode::fromString));
  }

  private ImmutableList<String> readReferencedResourcesFromMetadata() throws IOException {
    Optional<String> value = readMetadataValue(REFERENCED_RESOURCES);
    if (!value.isPresent()) {
      return ImmutableList.of();
    }
    return ObjectMappers.readValue(value.get(), new TypeReference<ImmutableList<String>>() {});
  }

  private Optional<String> readMetadataValue(String key) {
    Path path = getBuildable().getMetadataPath(getProjectFilesystem(), key);
    return getProjectFilesystem().readFileIfItExists(path);
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  static class BuildOutput {

    private final int weightEstimate;
    private final ImmutableSortedMap<String, HashCode> classnamesToHashes;
    private final ImmutableList<String> referencedResources;

    BuildOutput(
        int weightEstimate,
        ImmutableSortedMap<String, HashCode> classnamesToHashes,
        ImmutableList<String> referencedResources) {
      this.weightEstimate = weightEstimate;
      this.classnamesToHashes = classnamesToHashes;
      this.referencedResources = referencedResources;
    }

    @VisibleForTesting
    int getWeightEstimate() {
      return weightEstimate;
    }
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    // A .dex file is not guaranteed to be generated, so we return null to be conservative.
    return null;
  }

  @VisibleForTesting
  public ImmutableSortedSet<SourcePath> getDesugarDeps() {
    return getBuildable().desugarDeps;
  }

  public SourcePath getSourcePathToDex() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), getPathToDex());
  }

  public Path getPathToDex() {
    return getBuildable().getPathToDex(getProjectFilesystem());
  }

  public boolean hasOutput() {
    return !getClassNames().isEmpty();
  }

  ImmutableSortedMap<String, HashCode> getClassNames() {
    return buildOutputInitializer.getBuildOutput().classnamesToHashes;
  }

  int getWeightEstimate() {
    return buildOutputInitializer.getBuildOutput().weightEstimate;
  }

  ImmutableList<String> getReferencedResources() {
    return buildOutputInitializer.getBuildOutput().referencedResources;
  }

  @VisibleForTesting
  static Optional<String> getMetadataResources(
      ProjectFilesystem filesystem, BuildTarget buildTarget) {
    Path resourcesFile =
        BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s")
            .resolve(Impl.DEX_RULE_METADATA)
            .resolve(REFERENCED_RESOURCES);
    return filesystem.readFileIfItExists(resourcesFile);
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return buildDepsSupplier.get();
  }

  private Impl getBuildable() {
    return buildable;
  }
}
