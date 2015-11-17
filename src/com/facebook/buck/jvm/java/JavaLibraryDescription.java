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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.maven.AetherUtil;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

public class JavaLibraryDescription implements Description<JavaLibraryDescription.Arg>, Flavored {

  public static final BuildRuleType TYPE = BuildRuleType.of("java_library");
  public static final ImmutableSet<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      JavaLibrary.SRC_JAR,
      JavaLibrary.MAVEN_JAR);

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
    return SUPPORTED_FLAVORS.containsAll(flavors);
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

    ImmutableSortedSet<Flavor> flavors = target.getFlavors();
    BuildRuleParams paramsWithMavenFlavor = null;
    if (flavors.contains(JavaLibrary.MAVEN_JAR)) {
      paramsWithMavenFlavor = params;

      // Maven rules will depend upon their vanilla versions, so the latter have to be constructed
      // without the maven flavor to prevent output-path conflict
      params = params.copyWithBuildTarget(
          params.getBuildTarget().withoutFlavors(ImmutableSet.of(JavaLibrary.MAVEN_JAR)));
    }

    if (flavors.contains(JavaLibrary.SRC_JAR)) {
      args.mavenCoords = args.mavenCoords.transform(
          new Function<String, String>() {
            @Override
            public String apply(String input) {
              return AetherUtil.addClassifier(input, AetherUtil.CLASSIFIER_SOURCES);
            }
          });

      if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
        return new JavaSourceJar(
            params,
            pathResolver,
            args.srcs.get(),
            args.mavenCoords);
      } else {
        return MavenUberJar.SourceJar.create(
            Preconditions.checkNotNull(paramsWithMavenFlavor),
            pathResolver,
            args.srcs.get(),
            args.mavenCoords);
      }
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

    BuildTarget abiJarTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(CalculateAbi.FLAVOR)
            .build();

    ImmutableSortedSet<BuildRule> exportedDeps = resolver.getAllRules(args.exportedDeps.get());
    DefaultJavaLibrary defaultJavaLibrary =
        resolver.addToIndex(
            new DefaultJavaLibrary(
                params.appendExtraDeps(
                    Iterables.concat(
                        BuildRules.getExportedRules(
                            Iterables.concat(
                                params.getDeclaredDeps().get(),
                                exportedDeps,
                                resolver.getAllRules(args.providedDeps.get()))),
                        pathResolver.filterBuildRuleInputs(
                            javacOptions.getInputs(pathResolver)))),
                pathResolver,
                args.srcs.get(),
                validateResources(pathResolver, args, params.getProjectFilesystem()),
                args.proguardConfig.transform(
                    SourcePaths.toSourcePath(params.getProjectFilesystem())),
                args.postprocessClassesCommands.get(),
                exportedDeps,
                resolver.getAllRules(args.providedDeps.get()),
                new BuildTargetSourcePath(abiJarTarget),
                /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
                javacOptions,
                args.resourcesRoot,
                args.mavenCoords,
                args.tests.get()));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(defaultJavaLibrary.getBuildTarget())));

    addGwtModule(
        resolver,
        pathResolver,
        params,
        args);

    if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
      return defaultJavaLibrary;
    } else {
      return MavenUberJar.create(
          defaultJavaLibrary,
          Preconditions.checkNotNull(paramsWithMavenFlavor),
          pathResolver,
          args.mavenCoords);
    }
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
      Either<BuiltInJavac, SourcePath> either = args.compiler.get();

      if (either.isRight()) {
        SourcePath sourcePath = either.getRight();

        Optional<BuildRule> possibleRule = resolver.getRule(sourcePath);
        if (possibleRule.isPresent()) {
          BuildRule rule = possibleRule.get();
          if (rule instanceof PrebuiltJar) {
            builder.setJavacJarPath(
                new BuildTargetSourcePath(rule.getBuildTarget()));
          } else {
            throw new HumanReadableException("Only prebuilt_jar targets can be used as a javac");
          }
        } else {
          builder.setJavacPath(resolver.deprecatedGetPath(sourcePath));
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
   * Creates a {@link BuildRule} with the {@link JavaLibrary#GWT_MODULE_FLAVOR}, if appropriate.
   * <p>
   * If {@code arg.srcs} or {@code arg.resources} is non-empty, then the return value will not be
   * absent.
   */
  @VisibleForTesting
  static Optional<GwtModule> addGwtModule(
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      BuildRuleParams javaLibraryParams,
      Arg arg) {
    BuildTarget libraryTarget = javaLibraryParams.getBuildTarget();

    if (arg.srcs.get().isEmpty() &&
        arg.resources.get().isEmpty() &&
        !libraryTarget.isFlavored()) {
      return Optional.absent();
    }

    BuildTarget gwtModuleBuildTarget = BuildTarget.of(
        libraryTarget.getUnflavoredBuildTarget(),
        ImmutableSet.of(JavaLibrary.GWT_MODULE_FLAVOR));
    ImmutableSortedSet<SourcePath> filesForGwtModule = ImmutableSortedSet
        .<SourcePath>naturalOrder()
        .addAll(arg.srcs.get())
        .addAll(arg.resources.get())
        .build();

    // If any of the srcs or resources are BuildTargetSourcePaths, then their respective BuildRules
    // must be included as deps.
    ImmutableSortedSet<BuildRule> deps =
        ImmutableSortedSet.copyOf(pathResolver.filterBuildRuleInputs(filesForGwtModule));
    GwtModule gwtModule = new GwtModule(
        javaLibraryParams.copyWithChanges(
            gwtModuleBuildTarget,
            Suppliers.ofInstance(deps),
            /* extraDeps */ Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        pathResolver,
        filesForGwtModule);
    resolver.addToIndex(gwtModule);
    return Optional.of(gwtModule);
  }

  @SuppressFieldNotInitialized
  public static class Arg implements HasTests {
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

    @Hint(isDep = false) public Optional<ImmutableSortedSet<BuildTarget>> tests;

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

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests.get();
    }
  }
}
