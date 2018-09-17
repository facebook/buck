/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.core.rules.modern.annotations.DefaultFieldSerialization;
import com.facebook.buck.core.rules.pipeline.RulePipelineStateFactory;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaBuckConfig.UnusedDependenciesAction;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.CustomFieldSerialization;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.rules.modern.PipelinedBuildable;
import com.facebook.buck.rules.modern.PublicOutputPath;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.facebook.buck.rules.modern.impl.ModernBuildableSupport;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/** Buildable for DefaultJavaLibrary. */
class DefaultJavaLibraryBuildable implements PipelinedBuildable<JavacPipelineState> {
  @AddToRuleKey private final JarBuildStepsFactory jarBuildStepsFactory;
  @AddToRuleKey private final UnusedDependenciesAction unusedDependenciesAction;
  @AddToRuleKey private final Optional<NonHashableSourcePathContainer> sourceAbiOutput;

  @AddToRuleKey private final OutputPath rootOutputPath;
  @AddToRuleKey private final OutputPath pathToClassHashesOutputPath;
  @AddToRuleKey private final OutputPath annotationsOutputPath;

  @AddToRuleKey(stringify = true)
  @CustomFieldBehavior(DefaultFieldSerialization.class)
  private final BuildTarget buildTarget;

  @CustomFieldBehavior(SerializeAsEmptyOptional.class)
  private final Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory;

  DefaultJavaLibraryBuildable(
      BuildTarget buildTarget,
      ProjectFilesystem filesystem,
      JarBuildStepsFactory jarBuildStepsFactory,
      UnusedDependenciesAction unusedDependenciesAction,
      Optional<UnusedDependenciesFinderFactory> unusedDependenciesFinderFactory,
      @Nullable CalculateSourceAbi sourceAbi) {
    this.jarBuildStepsFactory = jarBuildStepsFactory;
    this.unusedDependenciesAction = unusedDependenciesAction;
    this.buildTarget = buildTarget;
    this.unusedDependenciesFinderFactory = unusedDependenciesFinderFactory;
    this.sourceAbiOutput =
        Optional.ofNullable(sourceAbi)
            .map(
                rule ->
                    new NonHashableSourcePathContainer(
                        Preconditions.checkNotNull(rule.getSourcePathToOutput())));

    CompilerOutputPaths outputPaths = CompilerOutputPaths.of(buildTarget, filesystem);

    Path pathToClassHashes = JavaLibraryRules.getPathToClassHashes(buildTarget, filesystem);
    this.pathToClassHashesOutputPath = new PublicOutputPath(pathToClassHashes);

    this.rootOutputPath = new PublicOutputPath(outputPaths.getOutputJarDirPath());
    this.annotationsOutputPath = new PublicOutputPath(outputPaths.getAnnotationPath());
  }

  public ImmutableSortedSet<SourcePath> getSources() {
    return jarBuildStepsFactory.getSources();
  }

  public ImmutableSortedSet<SourcePath> getResources() {
    return jarBuildStepsFactory.getResources();
  }

  public ImmutableSortedSet<SourcePath> getCompileTimeClasspathSourcePaths() {
    return jarBuildStepsFactory.getCompileTimeClasspathSourcePaths();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    ImmutableList<Step> factoryBuildSteps =
        jarBuildStepsFactory.getBuildStepsForLibraryJar(
            buildContext,
            filesystem,
            ModernBuildableSupport.getDerivedArtifactVerifier(buildTarget, filesystem, this),
            buildTarget,
            outputPathResolver.resolvePath(pathToClassHashesOutputPath));
    ImmutableList.Builder<Step> steps =
        ImmutableList.builderWithExpectedSize(factoryBuildSteps.size() + 1);
    steps.addAll(factoryBuildSteps);
    unusedDependenciesFinderFactory.ifPresent(factory -> steps.add(factory.create()));
    addMakeMissingOutputsStep(filesystem, outputPathResolver, steps);
    return steps.build();
  }

  @Override
  public ImmutableList<Step> getPipelinedBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      JavacPipelineState state,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    // TODO(cjhopman): unusedDependenciesFinder is broken.
    ImmutableList<Step> factoryBuildSteps =
        jarBuildStepsFactory.getPipelinedBuildStepsForLibraryJar(
            buildContext,
            filesystem,
            ModernBuildableSupport.getDerivedArtifactVerifier(buildTarget, filesystem, this),
            state,
            outputPathResolver.resolvePath(pathToClassHashesOutputPath));
    ImmutableList.Builder<Step> stepsBuilder =
        ImmutableList.builderWithExpectedSize(factoryBuildSteps.size() + 1);
    stepsBuilder.addAll(factoryBuildSteps);
    addMakeMissingOutputsStep(filesystem, outputPathResolver, stepsBuilder);
    return stepsBuilder.build();
  }

  public void addMakeMissingOutputsStep(
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      ImmutableList.Builder<Step> steps) {
    steps.add(
        new AbstractExecutionStep("make_missing_outputs") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws IOException {
            Path rootOutput = outputPathResolver.resolvePath(rootOutputPath);
            if (!filesystem.exists(rootOutput)) {
              filesystem.mkdirs(rootOutput);
            }
            Path pathToClassHashes = outputPathResolver.resolvePath(pathToClassHashesOutputPath);
            if (!filesystem.exists(pathToClassHashes)) {
              filesystem.createParentDirs(pathToClassHashes);
              filesystem.touch(pathToClassHashes);
            }
            Path annotationsPath = outputPathResolver.resolvePath(annotationsOutputPath);
            if (!filesystem.exists(annotationsPath)) {
              filesystem.mkdirs(annotationsPath);
            }
            return StepExecutionResults.SUCCESS;
          }
        });
  }

  public boolean useDependencyFileRuleKeys() {
    return jarBuildStepsFactory.useDependencyFileRuleKeys();
  }

  public Predicate<SourcePath> getCoveredByDepFilePredicate(
      SourcePathResolver pathResolver, SourcePathRuleFinder ruleFinder) {
    return jarBuildStepsFactory.getCoveredByDepFilePredicate(pathResolver, ruleFinder);
  }

  public Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver) {
    return jarBuildStepsFactory.getExistenceOfInterestPredicate(pathResolver);
  }

  public ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellPathResolver) {
    return jarBuildStepsFactory.getInputsAfterBuildingLocally(
        context, projectFilesystem, ruleFinder, cellPathResolver, buildTarget);
  }

  public boolean useRulePipelining() {
    return jarBuildStepsFactory.useRulePipelining();
  }

  public RulePipelineStateFactory<JavacPipelineState> getPipelineStateFactory() {
    return jarBuildStepsFactory;
  }

  private static class SerializeAsEmptyOptional<T>
      implements CustomFieldSerialization<Optional<T>> {
    @Override
    public <E extends Exception> void serialize(Optional<T> value, ValueVisitor<E> serializer) {}

    @Override
    public <E extends Exception> Optional<T> deserialize(ValueCreator<E> deserializer) {
      return Optional.empty();
    }
  }
}
