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

import com.facebook.buck.android.DexProducedFromJavaLibrary.BuildOutput;
import com.facebook.buck.dalvik.EstimateDexWeightStep;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.zip.ZipScrubberStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
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
    implements SupportsInputBasedRuleKey, InitializableFromDisk<BuildOutput> {

  @VisibleForTesting static final String WEIGHT_ESTIMATE = "weight_estimate";
  @VisibleForTesting static final String CLASSNAMES_TO_HASHES = "classnames_to_hashes";
  @VisibleForTesting static final String REFERENCED_RESOURCES = "referenced_resources";

  @AddToRuleKey private final SourcePath javaLibrarySourcePath;
  private final JavaLibrary javaLibrary;
  private final BuildOutputInitializer<BuildOutput> buildOutputInitializer;

  DexProducedFromJavaLibrary(BuildRuleParams params, JavaLibrary javaLibrary) {
    super(params);
    this.javaLibrary = javaLibrary;
    this.javaLibrarySourcePath = javaLibrary.getSourcePathToOutput();
    this.buildOutputInitializer = new BuildOutputInitializer<>(params.getBuildTarget(), this);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, final BuildableContext buildableContext) {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(RmStep.of(getProjectFilesystem(), getPathToDex()));

    // Make sure that the buck-out/gen/ directory exists for this.buildTarget.
    steps.add(MkdirStep.of(getProjectFilesystem(), getPathToDex().getParent()));

    // If there are classes, run dx.
    final ImmutableSortedMap<String, HashCode> classNamesToHashes =
        javaLibrary.getClassNamesToHashes();
    final boolean hasClassesToDx = !classNamesToHashes.isEmpty();
    final Supplier<Integer> weightEstimate;

    @Nullable final DxStep dx;

    if (hasClassesToDx) {
      Path pathToOutputFile =
          context.getSourcePathResolver().getAbsolutePath(javaLibrarySourcePath);
      EstimateDexWeightStep estimate =
          new EstimateDexWeightStep(getProjectFilesystem(), pathToOutputFile);
      steps.add(estimate);
      weightEstimate = estimate;

      // To be conservative, use --force-jumbo for these intermediate .dex files so that they can be
      // merged into a final classes.dex that uses jumbo instructions.
      dx =
          new DxStep(
              getProjectFilesystem(),
              getPathToDex(),
              Collections.singleton(pathToOutputFile),
              EnumSet.of(
                  DxStep.Option.USE_CUSTOM_DX_IF_AVAILABLE,
                  DxStep.Option.RUN_IN_PROCESS,
                  DxStep.Option.NO_OPTIMIZE,
                  DxStep.Option.FORCE_JUMBO));
      steps.add(dx);

      // The `DxStep` delegates to android tools to build a ZIP with timestamps in it, making
      // the output non-deterministic.  So use an additional scrubbing step to zero these out.
      steps.add(ZipScrubberStep.of(getProjectFilesystem().resolve(getPathToDex())));

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
              buildableContext.recordArtifact(getPathToDex());

              @Nullable Collection<String> referencedResources = dx.getResourcesReferencedInCode();
              if (referencedResources != null) {
                buildableContext.addMetadata(
                    REFERENCED_RESOURCES,
                    Ordering.natural().immutableSortedCopy(referencedResources));
              }
            }

            buildableContext.addMetadata(WEIGHT_ESTIMATE, String.valueOf(weightEstimate.get()));

            // Record the classnames to hashes map.
            buildableContext.addMetadata(
                CLASSNAMES_TO_HASHES,
                ObjectMappers.WRITER.writeValueAsString(
                    Maps.transformValues(classNamesToHashes, Object::toString)));

            return StepExecutionResult.SUCCESS;
          }
        };
    steps.add(recordArtifactAndMetadataStep);

    return steps.build();
  }

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    int weightEstimate = Integer.parseInt(onDiskBuildInfo.getValue(WEIGHT_ESTIMATE).get());
    Map<String, String> map =
        ObjectMappers.readValue(
            onDiskBuildInfo.getValue(CLASSNAMES_TO_HASHES).get(),
            new TypeReference<Map<String, String>>() {});
    Map<String, HashCode> classnamesToHashes = Maps.transformValues(map, HashCode::fromString);
    Optional<ImmutableList<String>> referencedResources =
        onDiskBuildInfo.getValues(REFERENCED_RESOURCES);
    return new BuildOutput(
        weightEstimate, ImmutableSortedMap.copyOf(classnamesToHashes), referencedResources);
  }

  @Override
  public BuildOutputInitializer<BuildOutput> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }

  static class BuildOutput {
    private final int weightEstimate;
    private final ImmutableSortedMap<String, HashCode> classnamesToHashes;
    private final Optional<ImmutableList<String>> referencedResources;

    BuildOutput(
        int weightEstimate,
        ImmutableSortedMap<String, HashCode> classnamesToHashes,
        Optional<ImmutableList<String>> referencedResources) {
      this.weightEstimate = weightEstimate;
      this.classnamesToHashes = classnamesToHashes;
      this.referencedResources = referencedResources;
    }
  }

  @Override
  @Nullable
  public SourcePath getSourcePathToOutput() {
    // A .dex file is not guaranteed to be generated, so we return null to be conservative.
    return null;
  }

  public Path getPathToDex() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s.dex.jar");
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

  Optional<ImmutableList<String>> getReferencedResources() {
    return buildOutputInitializer.getBuildOutput().referencedResources;
  }

  @VisibleForTesting
  static Sha1HashCode computeAbiKey(ImmutableSortedMap<String, HashCode> classNames) {
    Hasher hasher = Hashing.sha1().newHasher();
    for (Map.Entry<String, HashCode> entry : classNames.entrySet()) {
      hasher.putUnencodedChars(entry.getKey());
      hasher.putByte((byte) 0);
      hasher.putUnencodedChars(entry.getValue().toString());
      hasher.putByte((byte) 0);
    }
    return Sha1HashCode.fromHashCode(hasher.hash());
  }
}
