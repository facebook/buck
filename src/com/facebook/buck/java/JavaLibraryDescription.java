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
import com.facebook.buck.maven.AetherUtil;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.FlavorableDescription;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

public class JavaLibraryDescription implements Description<JavaLibraryDescription.Arg>,
    FlavorableDescription<JavaLibraryDescription.Arg>, Flavored {

  public static final BuildRuleType TYPE = BuildRuleType.of("java_library");

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
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildTarget target = params.getBuildTarget();

    // We know that the flavour we're being asked to create is valid, since the check is done when
    // creating the action graph from the target graph.

    if (target.getFlavors().contains(JavaLibrary.SRC_JAR)) {
      return new JavaSourceJar(
          params,
          pathResolver,
          args.srcs.get(),
          args.mavenCoords.transform(
            new Function<String, String>() {
              @Override
              public String apply(String input) {
                return AetherUtil.addClassifier(input, AetherUtil.CLASSIFIER_SOURCES);
              }
            }));
    }

    JavacOptions.Builder javacOptionsBuilder =
        JavaLibraryDescription.getJavacOptions(
            pathResolver,
            args,
            defaultOptions);
    AnnotationProcessingParams annotationParams =
        args.buildAnnotationProcessingParams(target, params.getProjectFilesystem(), resolver);
    javacOptionsBuilder.setAnnotationProcessingParams(annotationParams);
    JavacOptions javacOptions = javacOptionsBuilder.build();

    ImmutableSortedSet<BuildRule> exportedDeps = resolver.getAllRules(args.exportedDeps.get());
    return new DefaultJavaLibrary(
        params.appendExtraDeps(
            Iterables.concat(
                BuildRules.getExportedRules(
                    Iterables.concat(
                        params.getDeclaredDeps(),
                        exportedDeps,
                        resolver.getAllRules(args.providedDeps.get()))),
                pathResolver.filterBuildRuleInputs(
                    javacOptions.getInputs(pathResolver)))),
        pathResolver,
        args.srcs.get(),
        validateResources(pathResolver, args, params.getProjectFilesystem()),
        args.proguardConfig.transform(SourcePaths.toSourcePath(params.getProjectFilesystem())),
        args.postprocessClassesCommands.get(),
        exportedDeps,
        resolver.getAllRules(args.providedDeps.get()),
        /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
        javacOptions,
        args.resourcesRoot,
        args.mavenCoords);
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

  public static JavacOptions.Builder getJavacOptions(
      SourcePathResolver resolver,
      Arg args,
      JavacOptions defaultOptions) {
    if ((args.source.isPresent() || args.target.isPresent()) && args.javaVersion.isPresent()) {
      throw new HumanReadableException("Please set either source and target or java_version.");
    }

    JavacOptions.Builder builder = JavacOptions.builder(defaultOptions);

    if (args.javaVersion.isPresent()) {
      builder.setSourceLevel(args.javaVersion.get());
      builder.setTargetLevel(args.javaVersion.get());
    }

    if (args.source.isPresent()) {
      builder.setSourceLevel(args.source.get());
    }

    if (args.target.isPresent()) {
      builder.setTargetLevel(args.target.get());
    }

    if (args.extraArguments.isPresent()) {
      builder.addAllExtraArguments(args.extraArguments.get());
    }

    if (args.compiler.isPresent()) {
      Either<BuiltInJavac, SourcePath> left = args.compiler.get();

      if (left.isRight()) {
        SourcePath right = left.getRight();

        Optional<BuildRule> possibleRule = resolver.getRule(right);
        if (possibleRule.isPresent()) {
          BuildRule rule = possibleRule.get();
          if (rule instanceof PrebuiltJar) {
            builder.setJavacJarPath(
                new BuildTargetSourcePath(rule.getBuildTarget()));
          } else {
            throw new HumanReadableException("Only prebuilt_jar targets can be used as a javac");
          }
        } else {
          builder.setJavacPath(resolver.getPath(right));
        }
      }
    } else {
      if (args.javac.isPresent() || args.javacJar.isPresent()) {
        if (args.javac.isPresent() && args.javacJar.isPresent()) {
          throw new HumanReadableException("Cannot set both javac and javacjar");
        }
        builder.setJavacPath(args.javac);
        builder.setJavacJarPath(args.javacJar);
      }
    }

    return builder;
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
      BuildRuleResolver ruleResolver) {
    BuildTarget originalBuildTarget = buildRule.getBuildTarget();

    Optional<GwtModule> gwtModuleOptional = tryCreateGwtModule(
        new SourcePathResolver(ruleResolver),
        originalBuildTarget,
        projectFilesystem,
        ruleKeyBuilderFactory,
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
      Arg arg) {
    if (arg.srcs.get().isEmpty() &&
        arg.resources.get().isEmpty() &&
        !originalBuildTarget.isFlavored()) {
      return Optional.absent();
    }

    BuildTarget gwtModuleBuildTarget = BuildTargets.createFlavoredBuildTarget(
        originalBuildTarget.getUnflavoredBuildTarget(),
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
            Suppliers.ofInstance(deps),
            /* inferredDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
            projectFilesystem,
            ruleKeyBuilderFactory),
        resolver,
        filesForGwtModule);
    return Optional.of(gwtModule);
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableSortedSet<SourcePath>> resources;
    public Optional<String> source;
    public Optional<String> target;
    public Optional<String> javaVersion;
    public Optional<Path> javac;
    public Optional<SourcePath> javacJar;
    // I am not proud of this.
    public Optional<Either<BuiltInJavac, SourcePath>> compiler;
    public Optional<ImmutableList<String>> extraArguments;
    public Optional<Path> proguardConfig;
    public Optional<ImmutableSortedSet<BuildTarget>> annotationProcessorDeps;
    public Optional<ImmutableList<String>> annotationProcessorParams;
    public Optional<ImmutableSet<String>> annotationProcessors;
    public Optional<Boolean> annotationProcessorOnly;
    public Optional<ImmutableList<String>> postprocessClassesCommands;
    @Hint(isInput = false)
    public Optional<Path> resourcesRoot;
    public Optional<String> mavenCoords;

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
}
