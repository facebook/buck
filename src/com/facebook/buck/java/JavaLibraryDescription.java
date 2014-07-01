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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.ConstructorArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FlavorableDescription;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class JavaLibraryDescription implements Description<JavaLibraryDescription.Arg>,
    FlavorableDescription<JavaLibraryDescription.Arg> {

  public static final BuildRuleType TYPE = new BuildRuleType("java_library");
  public static final String ANNOTATION_PROCESSORS = "annotation_processors";
  @VisibleForTesting
  final JavaCompilerEnvironment javacEnv;

  public JavaLibraryDescription(JavaCompilerEnvironment javacEnv) {
    this.javacEnv = Preconditions.checkNotNull(javacEnv);
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> DefaultJavaLibrary createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    JavacOptions.Builder javacOptions = JavaLibraryDescription.getJavacOptions(args, javacEnv);

    AnnotationProcessingParams annotationParams =
        args.buildAnnotationProcessingParams(params.getBuildTarget());
    javacOptions.setAnnotationProcessingData(annotationParams);

    return new DefaultJavaLibrary(
        params,
        args.srcs.get(),
        validateResources(args, params.getProjectFilesystem()),
        args.proguardConfig,
        args.postprocessClassesCommands.get(),
        args.exportedDeps.get(),
        args.providedDeps.get(),
        /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
        javacOptions.build(),
        args.resourcesRoot);
  }

  // TODO(natthu): Consider adding a validateArg() method on Description which gets called before
  // createBuildable().
  public static ImmutableSortedSet<SourcePath> validateResources(
      Arg arg,
      ProjectFilesystem filesystem) {
    for (Path path : SourcePaths.filterInputsToCompareToOutput(arg.resources.get())) {
      if (!filesystem.exists(path)) {
        throw new HumanReadableException("Error: `resources` argument '%s' does not exist.", path);
      } else if (filesystem.isDirectory(path)) {
        throw new HumanReadableException(
            "Error: a directory is not a valid input to the `resources` argument: %s",
            path);
      }
    }
    return arg.resources.get();
  }

  public static JavacOptions.Builder getJavacOptions(Arg args, JavaCompilerEnvironment javacEnv) {
    JavacOptions.Builder javacOptions = JavacOptions.builder();

    String sourceLevel = args.source.or(javacEnv.getSourceLevel());
    String targetLevel = args.target.or(javacEnv.getTargetLevel());

    JavaCompilerEnvironment javacEnvToUse = new JavaCompilerEnvironment(
        javacEnv.getJavacPath(),
        javacEnv.getJavacVersion(),
        sourceLevel,
        targetLevel);

    javacOptions.setJavaCompilerEnviornment(javacEnvToUse);

    return javacOptions;
  }

  public static class Arg implements ConstructorArg {
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableSortedSet<SourcePath>> resources;
    public Optional<String> source;
    public Optional<String> target;
    public Optional<Path> proguardConfig;
    public Optional<ImmutableSortedSet<BuildRule>> annotationProcessorDeps;
    public Optional<ImmutableList<String>> annotationProcessorParams;
    public Optional<ImmutableSet<String>> annotationProcessors;
    public Optional<Boolean> annotationProcessorOnly;
    public Optional<ImmutableList<String>> postprocessClassesCommands;
    public Optional<Path> resourcesRoot;

    public Optional<ImmutableSortedSet<BuildRule>> providedDeps;
    public Optional<ImmutableSortedSet<BuildRule>> exportedDeps;
    public Optional<ImmutableSortedSet<BuildRule>> deps;

    public AnnotationProcessingParams buildAnnotationProcessingParams(BuildTarget owner) {
      ImmutableSet<String> annotationProcessors =
          this.annotationProcessors.or(ImmutableSet.<String>of());

      if (annotationProcessors.isEmpty()) {
        return AnnotationProcessingParams.EMPTY;
      }

      AnnotationProcessingParams.Builder builder = new AnnotationProcessingParams.Builder();
      builder.setOwnerTarget(owner);
      builder.addAllProcessors(annotationProcessors);
      ImmutableSortedSet<BuildRule> processorDeps =
          annotationProcessorDeps.or(ImmutableSortedSet.<BuildRule>of());
      for (BuildRule processorDep : processorDeps) {
        builder.addProcessorBuildTarget(processorDep);
      }
      for (String processorParam : annotationProcessorParams.or(ImmutableList.<String>of())) {
        builder.addParameter(processorParam);
      }
      builder.setProcessOnly(annotationProcessorOnly.or(Boolean.FALSE));

      return builder.build();
    }
  }

  /**
   * A {@JavaLibrary} registers a {@link JavaLibrary#GWT_MODULE_FLAVOR}, if appropriate.
   */
  @Override
  public void registerFlavors(
      Arg arg,
      BuildRule buildRule,
      ProjectFilesystem projectFilesystem,
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      BuildRuleResolver ruleResolver) {
    BuildTarget originalBuildTarget = buildRule.getBuildTarget();
    Optional<GwtModule> gwtModuleOptional = tryCreateGwtModule(
        originalBuildTarget,
        projectFilesystem,
        ruleKeyBuilderFactory,
        arg);
    if (!gwtModuleOptional.isPresent()) {
      return;
    }

    GwtModule gwtModule = gwtModuleOptional.get();
    ruleResolver.addToIndex(gwtModule.getBuildTarget(), gwtModule);
  }

  /**
   * Creates a {@link BuildRule} with the {@link JavaLibrary#GWT_MODULE_FLAVOR}, if appropriate.
   * <p>
   * If {@code arg.srcs} or {@code arg.resources} is non-empty, then the return value will not be
   * absent.
   */
  @VisibleForTesting
  static Optional<GwtModule> tryCreateGwtModule(
      BuildTarget originalBuildTarget,
      ProjectFilesystem projectFilesystem,
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      Arg arg) {
    if (arg.srcs.get().isEmpty() && arg.resources.get().isEmpty()) {
      return Optional.absent();
    }

    BuildTarget gwtModuleBuildTarget = BuildTargets.createFlavoredBuildTarget(
        originalBuildTarget,
        JavaLibrary.GWT_MODULE_FLAVOR);
    ImmutableSortedSet<SourcePath> filesForGwtModule = ImmutableSortedSet
        .<SourcePath>naturalOrder()
        .addAll(arg.srcs.get())
        .addAll(arg.resources.get())
        .build();

    // If any of the srcs or resources are BuildRuleSourcePaths, then their respective BuildRules
    // must be included as deps.
    ImmutableSortedSet<BuildRule> deps =
        ImmutableSortedSet.copyOf(SourcePaths.filterBuildRuleInputs(filesForGwtModule));
    GwtModule gwtModule = new GwtModule(
        new BuildRuleParams(
            gwtModuleBuildTarget,
            deps,
            /* inferredDeps */ ImmutableSortedSet.<BuildRule>of(),
            BuildTargetPattern.PUBLIC,
            projectFilesystem,
            ruleKeyBuilderFactory,
            BuildRuleType.GWT_MODULE),
        filesForGwtModule);
    return Optional.of(gwtModule);
  }
}
