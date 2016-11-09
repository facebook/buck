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

import static com.facebook.buck.jvm.common.ResourceValidator.validateResources;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.jvm.java.CalculateAbi;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaOptions;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.JavaTestDescription;
import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsFactory;
import com.facebook.buck.jvm.java.JavacToJarStepFactory;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.DependencyMode;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.Optional;

public class RobolectricTestDescription implements Description<RobolectricTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("robolectric_test");

  private final JavaOptions javaOptions;
  private final JavacOptions templateOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;

  public RobolectricTestDescription(
      JavaOptions javaOptions,
      JavacOptions templateOptions,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform) {
    this.javaOptions = javaOptions;
    this.templateOptions = templateOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
    this.cxxPlatform = cxxPlatform;
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
  public <A extends Arg> RobolectricTest createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ImmutableList<String> vmArgs = args.vmArgs;


    JavacOptions javacOptions =
        JavacOptionsFactory.create(
            templateOptions,
            params,
            resolver,
            pathResolver,
            args);

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        params.getBuildTarget(),
        params.copyWithExtraDeps(
            Suppliers.ofInstance(resolver.getAllRules(args.exportedDeps))),
        javacOptions,
        DependencyMode.TRANSITIVE,
        /* forceFinalResourceIds */ true,
        /* resourceUnionPackage */ Optional.empty(),
        /* rName */ Optional.empty());
    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.getBuildableForAndroidResources(
        resolver,
        /* createBuildableIfEmpty */ true);

    ImmutableSet<Path> additionalClasspathEntries = ImmutableSet.of();
    if (dummyRDotJava.isPresent()) {
      additionalClasspathEntries = ImmutableSet.of(dummyRDotJava.get().getPathToOutput());
      ImmutableSortedSet<BuildRule> newExtraDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
          .addAll(params.getExtraDeps().get())
          .add(dummyRDotJava.get())
          .build();
      params = params.copyWithExtraDeps(Suppliers.ofInstance(newExtraDeps));
    }

    JavaTestDescription.CxxLibraryEnhancement cxxLibraryEnhancement =
        new JavaTestDescription.CxxLibraryEnhancement(
            params,
            args.useCxxLibraries,
            args.cxxLibraryWhitelist,
            resolver,
            pathResolver,
            cxxPlatform);
    params = cxxLibraryEnhancement.updatedParams;

    BuildTarget abiJarTarget = params.getBuildTarget().withAppendedFlavors(CalculateAbi.FLAVOR);

    // Rewrite dependencies on tests to actually depend on the code which backs the test.
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
            params.getExtraDeps()
        )
        .withFlavor(JavaTest.COMPILED_TESTS_LIBRARY_FLAVOR);

    JavaLibrary testsLibrary =
        resolver.addToIndex(
            new DefaultJavaLibrary(
                testsLibraryParams,
                pathResolver,
                args.srcs,
                validateResources(
                    pathResolver,
                    params.getProjectFilesystem(),
                    args.resources),
                javacOptions.getGeneratedSourceFolderName(),
                args.proguardConfig.map(
                    SourcePaths.toSourcePath(params.getProjectFilesystem())::apply),
                /* postprocessClassesCommands */ ImmutableList.of(),
                /* exportDeps */ ImmutableSortedSet.of(),
                /* providedDeps */ ImmutableSortedSet.of(),
                new BuildTargetSourcePath(abiJarTarget),
                javacOptions.trackClassUsage(),
                additionalClasspathEntries,
                new JavacToJarStepFactory(javacOptions, new BootClasspathAppender()),
                args.resourcesRoot,
                args.manifestFile,
                args.mavenCoords,
                /* tests */ ImmutableSortedSet.of(),
                /* classesToRemoveFromJar */ ImmutableSet.of()));


    RobolectricTest robolectricTest =
        resolver.addToIndex(
            new RobolectricTest(
                params.copyWithDeps(
                    Suppliers.ofInstance(ImmutableSortedSet.of(testsLibrary)),
                    Suppliers.ofInstance(ImmutableSortedSet.of())),
                pathResolver,
                testsLibrary,
                additionalClasspathEntries,
                args.labels,
                args.contacts,
                TestType.JUNIT,
                javaOptions,
                vmArgs,
                cxxLibraryEnhancement.nativeLibsEnvironment,
                dummyRDotJava,
                args.testRuleTimeoutMs.map(Optional::of).orElse(defaultTestRuleTimeoutMs),
                args.testCaseTimeoutMs,
                args.env,
                args.getRunTestSeparately(),
                args.getForkMode(),
                args.stdOutLogLevel,
                args.stdErrLogLevel,
                args.robolectricRuntimeDependency,
                args.robolectricManifest));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(testsLibrary.getBuildTarget())));

    return robolectricTest;
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JavaTestDescription.Arg {
    public Optional<String> robolectricRuntimeDependency;
    public Optional<SourcePath> robolectricManifest;
  }
}
