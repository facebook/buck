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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FlavorableDescription;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class JavaLibraryDescription implements Description<JavaLibraryDescription.Arg>,
    FlavorableDescription<JavaLibraryDescription.Arg>, Flavored {

  public static final BuildRuleType TYPE = new BuildRuleType("java_library");

  @VisibleForTesting
  final JavacOptions defaultOptions;

  public JavaLibraryDescription(JavacOptions defaultOptions) {
    this.defaultOptions = defaultOptions;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return flavors.equals(ImmutableSet.of(JavaLibrary.SRC_JAR)) || flavors.isEmpty();
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildTarget target = params.getBuildTarget();

    // We know that the flavour we're being asked to create is valid, since the check is done when
    // creating the action graph from the target graph.

    if (target.getFlavors().contains(JavaLibrary.SRC_JAR)) {
      return new JavaSourceJar(params, pathResolver, args.srcs.get());
    }

    JavacOptions.Builder javacOptions = JavaLibraryDescription.getJavacOptions(
        args,
        defaultOptions);

    AnnotationProcessingParams annotationParams =
        args.buildAnnotationProcessingParams(target, params.getProjectFilesystem(), resolver);
    javacOptions.setAnnotationProcessingData(annotationParams);

    return new DefaultJavaLibrary(
        params,
        pathResolver,
        args.srcs.get(),
        validateResources(pathResolver, args, params.getProjectFilesystem()),
        args.proguardConfig,
        args.postprocessClassesCommands.get(),
        resolver.getAllRules(args.exportedDeps.get()),
        resolver.getAllRules(args.providedDeps.get()),
        /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
        javacOptions.build(),
        args.resourcesRoot);
  }

  // TODO(natthu): Consider adding a validateArg() method on Description which gets called before
  // createBuildable().
  public static ImmutableSortedSet<SourcePath> validateResources(
      SourcePathResolver resolver,
      Arg arg,
      ProjectFilesystem filesystem) {
    for (Path path : resolver.filterInputsToCompareToOutput(arg.resources.get())) {
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

  public static JavacOptions.Builder getJavacOptions(Arg args, JavacOptions defaultOptions) {
    JavacOptions.Builder builder = JavacOptions.builder(defaultOptions);

    if (args.source.isPresent()) {
      builder.setSourceLevel(args.source.get());
    }

    if (args.target.isPresent()) {
      builder.setTargetLevel(args.target.get());
    }

    return builder;
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableSortedSet<SourcePath>> resources;
    public Optional<String> source;
    public Optional<String> target;
    public Optional<Path> proguardConfig;
    public Optional<ImmutableSortedSet<BuildTarget>> annotationProcessorDeps;
    public Optional<ImmutableList<String>> annotationProcessorParams;
    public Optional<ImmutableSet<String>> annotationProcessors;
    public Optional<Boolean> annotationProcessorOnly;
    public Optional<ImmutableList<String>> postprocessClassesCommands;
    public Optional<Path> resourcesRoot;

    public Optional<ImmutableSortedSet<BuildTarget>> providedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> exportedDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;

    public AnnotationProcessingParams buildAnnotationProcessingParams(
        BuildTarget owner,
        ProjectFilesystem filesystem,
        BuildRuleResolver resolver) {
      ImmutableSet<String> annotationProcessors =
          this.annotationProcessors.or(ImmutableSet.<String>of());

      if (annotationProcessors.isEmpty()) {
        return AnnotationProcessingParams.EMPTY;
      }

      AnnotationProcessingParams.Builder builder = new AnnotationProcessingParams.Builder();
      builder.setOwnerTarget(owner);
      builder.addAllProcessors(annotationProcessors);
      builder.setProjectFilesystem(filesystem);
      ImmutableSortedSet<BuildRule> processorDeps =
          resolver.getAllRules(annotationProcessorDeps.or(ImmutableSortedSet.<BuildTarget>of()));
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
   * A {@link JavaLibrary} registers the ability to create {@link JavaLibrary#SRC_JAR}s when source
   * is present and also {@link JavaLibrary#GWT_MODULE_FLAVOR}, if appropriate.
   */
  @Override
  public void registerFlavors(
      Arg arg,
      BuildRule buildRule,
      ProjectFilesystem projectFilesystem,
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      TargetGraph targetGraph,
      BuildRuleResolver ruleResolver) {
    BuildTarget originalBuildTarget = buildRule.getBuildTarget();

    Optional<GwtModule> gwtModuleOptional = tryCreateGwtModule(
        new SourcePathResolver(ruleResolver),
        originalBuildTarget,
        projectFilesystem,
        ruleKeyBuilderFactory,
        targetGraph,
        arg);
    if (!gwtModuleOptional.isPresent()) {
      return;
    }

    GwtModule gwtModule = gwtModuleOptional.get();
    ruleResolver.addToIndex(gwtModule);
  }

  /**
   * Creates a {@link BuildRule} with the {@link JavaLibrary#GWT_MODULE_FLAVOR}, if appropriate.
   * <p>
   * If {@code arg.srcs} or {@code arg.resources} is non-empty, then the return value will not be
   * absent.
   */
  @VisibleForTesting
  static Optional<GwtModule> tryCreateGwtModule(
      SourcePathResolver resolver,
      BuildTarget originalBuildTarget,
      ProjectFilesystem projectFilesystem,
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      TargetGraph targetGraph,
      Arg arg) {
    if (arg.srcs.get().isEmpty() &&
        arg.resources.get().isEmpty() &&
        !originalBuildTarget.isFlavored()) {
      return Optional.absent();
    }

    BuildTarget gwtModuleBuildTarget = BuildTargets.createFlavoredBuildTarget(
        originalBuildTarget.getUnflavoredTarget(),
        JavaLibrary.GWT_MODULE_FLAVOR);
    ImmutableSortedSet<SourcePath> filesForGwtModule = ImmutableSortedSet
        .<SourcePath>naturalOrder()
        .addAll(arg.srcs.get())
        .addAll(arg.resources.get())
        .build();

    // If any of the srcs or resources are BuildTargetSourcePaths, then their respective BuildRules
    // must be included as deps.
    ImmutableSortedSet<BuildRule> deps =
        ImmutableSortedSet.copyOf(resolver.filterBuildRuleInputs(filesForGwtModule));
    GwtModule gwtModule = new GwtModule(
        new BuildRuleParams(
            gwtModuleBuildTarget,
            deps,
            /* inferredDeps */ ImmutableSortedSet.<BuildRule>of(),
            projectFilesystem,
            ruleKeyBuilderFactory,
            BuildRuleType.GWT_MODULE,
            targetGraph),
        resolver,
        filesForGwtModule);
    return Optional.of(gwtModule);
  }
}
