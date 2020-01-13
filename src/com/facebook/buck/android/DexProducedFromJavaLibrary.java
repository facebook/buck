/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import static com.facebook.buck.android.DexProducedFromJavaLibrary.MetadataResource.CLASSNAMES_TO_HASHES;
import static com.facebook.buck.android.DexProducedFromJavaLibrary.MetadataResource.REFERENCED_RESOURCES;
import static com.facebook.buck.android.DexProducedFromJavaLibrary.MetadataResource.WEIGHT_ESTIMATE;

import com.facebook.buck.android.DxStep.Option;
import com.facebook.buck.android.dalvik.EstimateDexWeightStep;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaClassHashesProvider;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.fs.TouchStep;
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
public class DexProducedFromJavaLibrary extends ModernBuildRule<DexProducedFromJavaLibrary.Impl>
    implements InitializableFromDisk<DexProducedFromJavaLibrary.BuildOutput>,
        TrimUberRDotJava.UsesResources {

  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  private final BuildTarget javaLibraryBuildTarget;

  public DexProducedFromJavaLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      AndroidPlatformTarget androidPlatformTarget,
      JavaLibrary javaLibrary,
      String dexTool,
      int weightFactor,
      ImmutableSortedSet<BuildRule> desugarDeps) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            projectFilesystem,
            dexTool,
            weightFactor,
            getDesugarClassPaths(desugarDeps),
            androidPlatformTarget,
            javaLibrary));
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
    this.javaLibraryBuildTarget = javaLibrary.getBuildTarget();
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
  static class Impl implements Buildable {

    private static final String DEX_RULE_METADATA = "metadata";

    @AddToRuleKey private final String dexTool;
    // Scale factor to apply to our weight estimate, for deceptive dexes.
    @AddToRuleKey private final int weightFactor;
    @AddToRuleKey private final ImmutableSortedSet<SourcePath> desugarDeps;
    @AddToRuleKey private final SourcePath javaLibrarySourcePath;
    @AddToRuleKey private final AndroidPlatformTarget androidPlatformTarget;
    @AddToRuleKey private final boolean desugarEnabled;
    @AddToRuleKey private final JavaClassHashesProvider javaClassHashesProvider;

    @AddToRuleKey private final OutputPath outputDex;
    @AddToRuleKey private final OutputPath metadataWeight;
    @AddToRuleKey private final OutputPath metadataClassnamesToHashes;
    @AddToRuleKey private final OutputPath metadataReferencedResources;

    Impl(
        ProjectFilesystem projectFilesystem,
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
      this.javaClassHashesProvider = javaLibrary.getClassHashesProvider();

      this.outputDex = new OutputPath(projectFilesystem.getPath("dex.jar"));
      Path metadataDir = projectFilesystem.getPath(DEX_RULE_METADATA);
      this.metadataWeight = new OutputPath(metadataDir.resolve(WEIGHT_ESTIMATE.toString()));
      this.metadataClassnamesToHashes =
          new OutputPath(metadataDir.resolve(CLASSNAMES_TO_HASHES.toString()));
      this.metadataReferencedResources =
          new OutputPath(metadataDir.resolve(REFERENCED_RESOURCES.toString()));
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      SourcePathResolverAdapter sourcePathResolverAdapter = buildContext.getSourcePathResolver();
      ImmutableList.Builder<Step> steps = ImmutableList.builder();

      // If there are classes, run dx.
      ImmutableSortedMap<String, HashCode> classNamesToHashes =
          javaClassHashesProvider.getClassNamesToHashes(filesystem, sourcePathResolverAdapter);
      boolean hasClassesToDx = !classNamesToHashes.isEmpty();
      Supplier<Integer> weightEstimate;

      @Nullable DxStep dx;

      Path pathToDex = outputPathResolver.resolvePath(outputDex);
      if (hasClassesToDx) {
        Path pathToOutputFile = sourcePathResolverAdapter.getAbsolutePath(javaLibrarySourcePath);
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
                getAbsolutePaths(desugarDeps, sourcePathResolverAdapter),
                Optional.empty(),
                Optional.empty() /* minSdkVersion */);
        steps.add(dx);

        // The `DxStep` delegates to android tools to build a ZIP with timestamps in it, making
        // the output non-deterministic.  So use an additional scrubbing step to zero these out.
        steps.add(ZipScrubberStep.of(filesystem.resolve(pathToDex)));

      } else {
        dx = null;
        weightEstimate = Suppliers.ofInstance(0);
        // Create an empty file so the dex output can be used in input rulekeys
        steps.add(new TouchStep(filesystem, pathToDex));
      }

      // Run a step to record artifacts and metadata. The values recorded depend upon whether dx was
      // run.
      String stepName = hasClassesToDx ? "record_dx_success" : "record_empty_dx";
      AbstractExecutionStep recordArtifactAndMetadataStep =
          new AbstractExecutionStep(stepName) {

            @Override
            public StepExecutionResult execute(ExecutionContext context) throws IOException {
              if (hasClassesToDx) {

                @Nullable
                Collection<String> referencedResources = dx.getResourcesReferencedInCode();
                if (referencedResources != null) {
                  writeMetadataValue(
                      REFERENCED_RESOURCES,
                      ObjectMappers.WRITER.writeValueAsString(
                          Ordering.natural().immutableSortedCopy(referencedResources)));
                }
              }

              writeMetadataValue(
                  WEIGHT_ESTIMATE, String.valueOf(weightFactor * weightEstimate.get()));

              // Record the classnames to hashes map.
              writeMetadataValue(
                  CLASSNAMES_TO_HASHES,
                  ObjectMappers.WRITER.writeValueAsString(
                      Maps.transformValues(classNamesToHashes, Object::toString)));

              return StepExecutionResults.SUCCESS;
            }

            private void writeMetadataValue(MetadataResource metadataResource, String value)
                throws IOException {
              Path path = metadataResource.getMetadataPath(Impl.this, outputPathResolver);
              filesystem.mkdirs(path.getParent());
              filesystem.writeContentsToPath(value, path);
            }
          };
      steps.add(recordArtifactAndMetadataStep);

      return steps.build();
    }

    private Collection<Path> getAbsolutePaths(
        Collection<SourcePath> sourcePaths, SourcePathResolverAdapter sourcePathResolverAdapter) {
      return sourcePaths.stream()
          .filter(Objects::nonNull)
          .map(sourcePathResolverAdapter::getAbsolutePath)
          .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
    }
  }

  /** Metadata Resource enum */
  enum MetadataResource {
    WEIGHT_ESTIMATE,
    CLASSNAMES_TO_HASHES,
    REFERENCED_RESOURCES;

    Path getMetadataPath(Impl buildable, OutputPathResolver outputPathResolver) {
      OutputPath outputPath;
      switch (this) {
        case WEIGHT_ESTIMATE:
          outputPath = buildable.metadataWeight;
          break;
        case CLASSNAMES_TO_HASHES:
          outputPath = buildable.metadataClassnamesToHashes;
          break;
        case REFERENCED_RESOURCES:
          outputPath = buildable.metadataReferencedResources;
          break;
        default:
          throw new IllegalStateException(this + " is not supported");
      }
      return outputPathResolver.resolvePath(outputPath);
    }

    @Override
    public String toString() {
      return name().toLowerCase();
    }
  }

  private static ImmutableSortedSet<SourcePath> getDesugarClassPaths(
      Collection<BuildRule> desugarDeps) {
    if (desugarDeps == null) {
      return ImmutableSortedSet.of();
    }
    return desugarDeps.stream()
        .map(BuildRule::getSourcePathToOutput)
        .filter(Objects::nonNull)
        .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
  }

  @Override
  public BuildOutput initializeFromDisk(SourcePathResolverAdapter pathResolver) throws IOException {
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

  private Optional<String> readMetadataValue(MetadataResource metadataResource) {
    Path path = metadataResource.getMetadataPath(getBuildable(), getOutputPathResolver());
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

  BuildTargetSourcePath getSourcePathToDex() {
    return getSourcePath(getPathToDex());
  }

  public OutputPath getPathToDex() {
    return getBuildable().outputDex;
  }

  public boolean hasOutput() {
    return !getClassNamesToHashes().isEmpty();
  }

  ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
    return buildOutputInitializer.getBuildOutput().classnamesToHashes;
  }

  int getWeightEstimate() {
    return buildOutputInitializer.getBuildOutput().weightEstimate;
  }

  @Override
  public ImmutableList<String> getReferencedResources() {
    return buildOutputInitializer.getBuildOutput().referencedResources;
  }

  @VisibleForTesting
  static Optional<String> getMetadataResources(
      ProjectFilesystem filesystem, BuildTarget buildTarget) {
    Path resourcesFile =
        BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s")
            .resolve(Impl.DEX_RULE_METADATA)
            .resolve(REFERENCED_RESOURCES.toString());
    return filesystem.readFileIfItExists(resourcesFile);
  }

  public BuildTarget getJavaLibraryBuildTarget() {
    return javaLibraryBuildTarget;
  }
}
