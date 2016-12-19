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

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.jvm.common.ResourceValidator;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Predicates;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;


public class JavaTestDescription implements
    Description<JavaTestDescription.Arg>,
    ImplicitDepsInferringDescription<JavaTestDescription.Arg> {

  private final JavaOptions javaOptions;
  private final JavacOptions templateJavacOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;

  public JavaTestDescription(
      JavaOptions javaOptions,
      JavacOptions templateOptions,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform) {
    this.javaOptions = javaOptions;
    this.templateJavacOptions = templateOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatform = cxxPlatform;
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
      A args) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    if (params.getBuildTarget().getFlavors().contains(CalculateAbi.FLAVOR)) {
      BuildTarget testTarget = params.getBuildTarget().withoutFlavors(CalculateAbi.FLAVOR);
      resolver.requireRule(testTarget);
      return CalculateAbi.of(
          params.getBuildTarget(),
          pathResolver,
          params,
          new BuildTargetSourcePath(testTarget));
    }

    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            templateJavacOptions,
            params,
            resolver,
            pathResolver,
            args
        );

    CxxLibraryEnhancement cxxLibraryEnhancement = new CxxLibraryEnhancement(
        params,
        args.useCxxLibraries,
        args.cxxLibraryWhitelist,
        resolver,
        pathResolver,
        cxxPlatform);
    params = cxxLibraryEnhancement.updatedParams;

    BuildTarget abiJarTarget = params.getBuildTarget().withAppendedFlavors(CalculateAbi.FLAVOR);

    BuildRuleParams testsLibraryParams = params.copyWithDeps(
            Suppliers.ofInstance(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(params.getDeclaredDeps().get())
                    .addAll(BuildRules.getExportedRules(
                        Iterables.concat(
                            params.getDeclaredDeps().get(),
                            resolver.getAllRules(args.providedDeps))))
                    .addAll(pathResolver.filterBuildRuleInputs(
                        javacOptions.getInputs(pathResolver)))
                    .build()
            ),
            params.getExtraDeps())
        .withFlavor(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);

    JavaLibrary testsLibrary =
        resolver.addToIndex(
            new DefaultJavaLibrary(
                testsLibraryParams,
                pathResolver,
                args.srcs,
                ResourceValidator.validateResources(
                    pathResolver,
                    params.getProjectFilesystem(),
                    args.resources),
                javacOptions.getGeneratedSourceFolderName(),
                args.proguardConfig.map(
                    SourcePaths.toSourcePath(params.getProjectFilesystem())::apply),
                /* postprocessClassesCommands */ ImmutableList.of(),
                /* exportDeps */ ImmutableSortedSet.of(),
                resolver.getAllRules(args.providedDeps),
                abiJarTarget,
                JavaLibraryRules.getAbiInputs(resolver, testsLibraryParams.getDeps()),
                javacOptions.trackClassUsage(),
                /* additionalClasspathEntries */ ImmutableSet.of(),
                new JavacToJarStepFactory(javacOptions, JavacOptionsAmender.IDENTITY),
                args.resourcesRoot,
                args.manifestFile,
                args.mavenCoords,
                /* tests */ ImmutableSortedSet.of(),
                /* classesToRemoveFromJar */ ImmutableSet.of()
            ));

    return new JavaTest(
        params.copyWithDeps(
            Suppliers.ofInstance(ImmutableSortedSet.of(testsLibrary)),
            Suppliers.ofInstance(ImmutableSortedSet.of())),
        pathResolver,
        testsLibrary,
        /* additionalClasspathEntries */ ImmutableSet.of(),
        args.labels,
        args.contacts,
        args.testType.orElse(TestType.JUNIT),
        javaOptions.getJavaRuntimeLauncher(),
        args.vmArgs,
        cxxLibraryEnhancement.nativeLibsEnvironment,
        args.testRuleTimeoutMs.map(Optional::of).orElse(defaultTestRuleTimeoutMs),
        args.testCaseTimeoutMs,
        args.env,
        args.getRunTestSeparately(),
        args.getForkMode(),
        args.stdOutLogLevel,
        args.stdErrLogLevel);
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
    if (constructorArg.useCxxLibraries.orElse(false)) {
      deps.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatform));
    }
    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JavaLibraryDescription.Arg {
    public ImmutableSortedSet<String> contacts = ImmutableSortedSet.of();
    public ImmutableSortedSet<Label> labels = ImmutableSortedSet.of();
    public ImmutableList<String> vmArgs = ImmutableList.of();
    public Optional<TestType> testType;
    public Optional<Boolean> runTestSeparately;
    public Optional<ForkMode> forkMode;
    public Optional<Level> stdErrLogLevel;
    public Optional<Level> stdOutLogLevel;
    public Optional<Boolean> useCxxLibraries;
    public ImmutableSet<BuildTarget> cxxLibraryWhitelist = ImmutableSet.of();
    public Optional<Long> testRuleTimeoutMs;
    public Optional<Long> testCaseTimeoutMs;
    public ImmutableMap<String, String> env = ImmutableMap.of();

    public boolean getRunTestSeparately() {
      return runTestSeparately.orElse(false);
    }
    public ForkMode getForkMode() {
      return forkMode.orElse(ForkMode.NONE);
    }
  }

  public static class CxxLibraryEnhancement {
    public final BuildRuleParams updatedParams;
    public final ImmutableMap<String, String> nativeLibsEnvironment;

    public CxxLibraryEnhancement(
        BuildRuleParams params,
        Optional<Boolean> useCxxLibraries,
        final ImmutableSet<BuildTarget> cxxLibraryWhitelist,
        BuildRuleResolver resolver,
        SourcePathResolver pathResolver,
        CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
      if (useCxxLibraries.orElse(false)) {
        SymlinkTree nativeLibsSymlinkTree =
            buildNativeLibsSymlinkTreeRule(params, pathResolver, cxxPlatform);

        // If the cxxLibraryWhitelist is present, remove symlinks that were not requested.
        // They could point to old, invalid versions of the library in question.
        if (!cxxLibraryWhitelist.isEmpty()) {
          ImmutableMap.Builder<Path, SourcePath> filteredLinks = ImmutableMap.builder();
          for (Map.Entry<Path, SourcePath> entry : nativeLibsSymlinkTree.getLinks().entrySet()) {
            if (!(entry.getValue() instanceof BuildTargetSourcePath)) {
              // Could consider including these, but I don't know of any examples.
              continue;
            }
            BuildTargetSourcePath sourcePath = (BuildTargetSourcePath) entry.getValue();
            if (cxxLibraryWhitelist.contains(sourcePath.getTarget().withFlavors())) {
              filteredLinks.put(entry.getKey(), entry.getValue());
            }
          }
          nativeLibsSymlinkTree = new SymlinkTree(
              params.copyWithChanges(
                  nativeLibsSymlinkTree.getBuildTarget(),
                  Suppliers.ofInstance(ImmutableSortedSet.of()),
                  Suppliers.ofInstance(ImmutableSortedSet.of())),
              pathResolver,
              nativeLibsSymlinkTree.getRoot(),
              filteredLinks.build());
        }

        updatedParams = params.appendExtraDeps(ImmutableList.<BuildRule>builder()
            .add(nativeLibsSymlinkTree)
            // Add all the native libraries as first-order dependencies.
            // This has two effects:
            // (1) They become runtime deps because JavaTest adds all first-order deps.
            // (2) They affect the JavaTest's RuleKey, so changing them will invalidate
            // the test results cache.
            .addAll(pathResolver.filterBuildRuleInputs(nativeLibsSymlinkTree.getLinks().values()))
            .build());
        nativeLibsEnvironment =
            ImmutableMap.of(
                cxxPlatform.getLd().resolve(resolver).searchPathEnvVar(),
                nativeLibsSymlinkTree.getRoot().toString());
      } else {
        updatedParams = params;
        nativeLibsEnvironment = ImmutableMap.of();
      }
    }

    public static SymlinkTree buildNativeLibsSymlinkTreeRule(
        BuildRuleParams buildRuleParams,
        SourcePathResolver pathResolver,
        CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
      return CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
          buildRuleParams,
          pathResolver,
          cxxPlatform,
          buildRuleParams.getDeps(),
          Predicates.or(
              NativeLinkable.class::isInstance,
              JavaLibrary.class::isInstance));
    }
  }
}
