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

package com.facebook.buck.android;

import com.facebook.buck.android.PreDexMerge.BuildOutput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Buildable that is responsible for:
 * <ul>
 *   <li>Bucketing pre-dexed jars into lists for primary and secondary dex files
 *       (if the app is split-dex).
 *   <li>Merging the pre-dexed jars into primary and secondary dex files.
 *   <li>Writing the split-dex "metadata.txt".
 * </ul>
 * <p>
 * Clients of this Buildable may need to know:
 * <ul>
 *   <li>The locations of the zip files directories containing secondary dex files and metadata.
 * </ul>
 *
 * This uses a separate implementation from addDexingSteps.
 * The differences in the splitting logic are too significant to make it
 * worth merging them.
 */
public class PreDexMerge extends AbstractBuildable implements InitializableFromDisk<BuildOutput> {

  private static final String SECONDARY_DEX_DIRECTORIES_KEY = "secondary_dex_directories";

  private final BuildTarget buildTarget;
  private final Path primaryDexPath;
  private final DexSplitMode dexSplitMode;
  private final ImmutableSet<IntermediateDexRule> preDexDeps;
  private final UberRDotJava uberRDotJava;

  public PreDexMerge(
      BuildTarget buildTarget,
      Path primaryDexPath,
      DexSplitMode dexSplitMode,
      ImmutableSet<IntermediateDexRule> preDexDeps,
      UberRDotJava uberRDotJava) {
    this.buildTarget = Preconditions.checkNotNull(buildTarget);
    this.primaryDexPath = Preconditions.checkNotNull(primaryDexPath);
    this.dexSplitMode = Preconditions.checkNotNull(dexSplitMode);
    this.preDexDeps = Preconditions.checkNotNull(preDexDeps);
    this.uberRDotJava = Preconditions.checkNotNull(uberRDotJava);
  }

  @Override
  public Collection<Path> getInputsToCompareToOutput() {
    return SourcePaths.filterInputsToCompareToOutput(dexSplitMode.getSourcePaths());
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(new MkdirStep(primaryDexPath.getParent()));

    if (dexSplitMode.isShouldSplitDex()) {
      addStepsForSplitDex(steps, context, buildableContext);
    } else {
      addStepsForSingleDex(steps, buildableContext);
    }
    return steps.build();
  }

  private void addStepsForSplitDex(
      ImmutableList.Builder<Step> steps,
      BuildContext context,
      final BuildableContext buildableContext) {

    // Collect all of the DexWithClasses objects to use for merging.
    ImmutableList<DexWithClasses> dexFilesToMerge = FluentIterable.from(preDexDeps)
        .transform(DexWithClasses.TO_DEX_WITH_CLASSES)
        .filter(Predicates.notNull())
        .toList();

    // Create all of the output paths needed for the SmartDexingStep.
    Path secondaryDexScratchDir = BuildTargets.getBinPath(buildTarget, "__%s_secondary_dex__");
    Path secondaryDexMetadataScratchDir = secondaryDexScratchDir.resolve("metadata");
    Path secondaryDexJarFilesScratchDir = secondaryDexScratchDir.resolve("jarfiles");
    final ImmutableSet<Path> secondaryDexDirectories = ImmutableSet.of(
        secondaryDexMetadataScratchDir, secondaryDexJarFilesScratchDir);


    final Path secondaryDexMetadataDir =
        secondaryDexMetadataScratchDir.resolve(AndroidBinaryRule.SECONDARY_DEX_SUBDIR);
    final Path secondaryDexJarFilesDir =
        secondaryDexJarFilesScratchDir.resolve(AndroidBinaryRule.SECONDARY_DEX_SUBDIR);
    steps.add(new MakeCleanDirectoryStep(secondaryDexMetadataDir));
    // Do not clear existing directory which might contain secondary dex files that are not
    // re-merged (since their contents did not change).
    steps.add(new MkdirStep(secondaryDexJarFilesDir));

    Path preDexScratchDir = secondaryDexScratchDir.resolve("__bucket_pre_dex__");
    steps.add(new MakeCleanDirectoryStep(preDexScratchDir));

    Path successDir = BuildTargets.getBinPath(buildTarget, "__%s_merge_pre_dex__/.success");
    steps.add(new MkdirStep(successDir));

    buildableContext.addMetadata(
        SECONDARY_DEX_DIRECTORIES_KEY,
        Iterables.transform(secondaryDexDirectories, Functions.toStringFunction())
    );

    buildableContext.recordArtifact(primaryDexPath);
    buildableContext.recordArtifactsInDirectory(secondaryDexJarFilesDir);
    buildableContext.recordArtifactsInDirectory(secondaryDexMetadataDir);
    buildableContext.recordArtifactsInDirectory(successDir);

    PreDexedFilesSorter preDexedFilesSorter = new PreDexedFilesSorter(
        uberRDotJava.getRDotJavaDexWithClasses(),
        dexFilesToMerge,
        dexSplitMode.getPrimaryDexPatterns(),
        preDexScratchDir,
        dexSplitMode.getLinearAllocHardLimit(),
        dexSplitMode.getDexStore(),
        secondaryDexJarFilesDir);
    final PreDexedFilesSorter.Result sortResult =
        preDexedFilesSorter.sortIntoPrimaryAndSecondaryDexes(context, steps);

    steps.add(new SmartDexingStep(
        primaryDexPath,
        Suppliers.ofInstance(sortResult.primaryDexInputs),
        Optional.of(secondaryDexJarFilesDir),
        Optional.of(Suppliers.ofInstance(sortResult.secondaryOutputToInputs)),
        successDir,
        /* numThreads */ Optional.<Integer>absent(),
        AndroidBinaryRule.DX_MERGE_OPTIONS));

    steps.add(new AbstractExecutionStep("write_metadata_txt") {
      @Override
      public int execute(ExecutionContext executionContext) {
        ProjectFilesystem filesystem = executionContext.getProjectFilesystem();
        Map<Path, DexWithClasses> metadataTxtEntries = sortResult.metadataTxtDexEntries;
        List<String> lines = Lists.newArrayListWithCapacity(metadataTxtEntries.size());
        try {
          for (Map.Entry<Path, DexWithClasses> entry : metadataTxtEntries.entrySet()) {
            Path pathToSecondaryDex = entry.getKey();
            String containedClass = Iterables.get(entry.getValue().getClassNames(), 0);
            containedClass = containedClass.replace('/', '.');
            String hash = filesystem.computeSha1(pathToSecondaryDex);
            lines.add(String.format("%s %s %s",
                pathToSecondaryDex.getFileName(), hash, containedClass));
          }
          filesystem.writeLinesToPath(lines, secondaryDexMetadataDir.resolve("metadata.txt"));
        } catch (IOException e) {
          executionContext.logError(e, "Failed when writing metadata.txt multi-dex.");
          return 1;
        }
        return 0;
      }
    });
  }

  private void addStepsForSingleDex(
      ImmutableList.Builder<Step> steps,
      final BuildableContext buildableContext) {
    // For single-dex apps with pre-dexing, we just add the steps directly.
    Iterable<Path> filesToDex = FluentIterable.from(preDexDeps)
        .transform(
            new Function<IntermediateDexRule, Path>() {
              @Override
              @Nullable
              public Path apply(IntermediateDexRule preDexDep) {
                DexProducedFromJavaLibraryThatContainsClassFiles preDex = preDexDep
                    .getBuildable();
                if (preDex.hasOutput()) {
                  return preDex.getPathToDex();
                } else {
                  return null;
                }
              }
            })
        .filter(Predicates.notNull());

    // If this APK has Android resources, then the generated R.class files also need to be dexed.
    Optional<DexWithClasses> rDotJavaDexWithClasses = uberRDotJava.getRDotJavaDexWithClasses();
    if (rDotJavaDexWithClasses.isPresent()) {
      filesToDex = Iterables.concat(
          filesToDex,
          Collections.singleton(rDotJavaDexWithClasses.get().getPathToDexFile()));
    }

    buildableContext.recordArtifact(primaryDexPath);

    // This will combine the pre-dexed files and the R.class files into a single classes.dex file.
    steps.add(new DxStep(primaryDexPath, filesToDex, AndroidBinaryRule.DX_MERGE_OPTIONS));

    buildableContext.addMetadata(
        SECONDARY_DEX_DIRECTORIES_KEY,
        ImmutableSet.<String>of()
    );
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) throws IOException {
    dexSplitMode.appendToRuleKey("dexSplitMode", builder);
    return builder;
  }

  @Nullable
  @Override
  public Path getPathToOutputFile() {
    return null;
  }

  @Nullable
  public ImmutableSet<Path> getSecondaryDexDirectories() {
    return getBuildOutput().secondaryDexDirectories;
  }

  static class BuildOutput {
    private final ImmutableSet<Path> secondaryDexDirectories;

    BuildOutput(ImmutableSet<Path> secondaryDexDirectories) {
      this.secondaryDexDirectories = Preconditions.checkNotNull(secondaryDexDirectories);
    }
  }

  @Nullable
  private BuildOutput buildOutput;

  @Override
  public BuildOutput initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    return new BuildOutput(
        FluentIterable.from(onDiskBuildInfo.getValues(SECONDARY_DEX_DIRECTORIES_KEY).get())
            .transform(MorePaths.TO_PATH)
            .toSet());
  }

  @Override
  public void setBuildOutput(BuildOutput buildOutput) {
    Preconditions.checkState(this.buildOutput == null,
        "buildOutput should not already be set for %s.",
        this);
    this.buildOutput = buildOutput;
  }

  @Override
  public BuildOutput getBuildOutput() {
    Preconditions.checkState(buildOutput != null, "buildOutput must already be set for %s.", this);
    return buildOutput;
  }

  public static Builder newPreDexMergeBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  static class Builder extends AbstractBuildable.Builder {

    @Nullable private Path primaryDexPath;
    @Nullable private DexSplitMode dexSplitMode;
    @Nullable private ImmutableSet<IntermediateDexRule> preDexDeps;
    @Nullable private UberRDotJava uberRDotJava;


    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    protected BuildRuleType getType() {
      return BuildRuleType.DEX_MERGE;
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    public Builder setPrimaryDexPath(Path primaryDexPath) {
      this.primaryDexPath = primaryDexPath;
      return this;
    }

    public Builder setDexSplitMode(DexSplitMode dexSplitMode) {
      this.dexSplitMode = dexSplitMode;
      return this;
    }

    public Builder setPreDexDeps(ImmutableSet<IntermediateDexRule> preDexDeps) {
      this.preDexDeps = preDexDeps;
      for (BuildRule dep : preDexDeps) {
        addDep(dep.getBuildTarget());
      }
      return this;
    }

    public Builder setUberRDotJava(UberRDotJava uberRDotJava) {
      this.uberRDotJava = uberRDotJava;
      addDep(uberRDotJava.getBuildTarget());
      return this;
    }

    @Override
    protected PreDexMerge newBuildable(BuildRuleParams params, BuildRuleResolver resolver) {
      return new PreDexMerge(
          buildTarget,
          primaryDexPath,
          dexSplitMode,
          preDexDeps,
          uberRDotJava);
    }
  }
}
