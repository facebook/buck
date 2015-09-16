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

import com.facebook.buck.android.AndroidLibraryGraphEnhancer.ResourceDependencyMode;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.java.AnnotationProcessingParams;
import com.facebook.buck.java.CalculateAbi;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

public class RobolectricTestDescription implements Description<RobolectricTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("robolectric_test");

  private final JavacOptions templateOptions;
  private final Optional<Long> testRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;
  private final Optional<Path> testTempDirOverride;

  public RobolectricTestDescription(
      JavacOptions templateOptions,
      Optional<Long> testRuleTimeoutMs,
      CxxPlatform cxxPlatform,
      Optional<Path> testTempDirOverride) {
    this.templateOptions = templateOptions;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
    this.cxxPlatform = cxxPlatform;
    this.testTempDirOverride = testTempDirOverride;
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
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    ImmutableList<String> vmArgs = args.vmArgs.get();

    JavacOptions.Builder javacOptionsBuilder =
        JavaLibraryDescription.getJavacOptions(
            pathResolver,
            args,
            templateOptions);
    AnnotationProcessingParams annotationParams =
        args.buildAnnotationProcessingParams(
            params.getBuildTarget(),
            params.getProjectFilesystem(),
            resolver);
    javacOptionsBuilder.setAnnotationProcessingParams(annotationParams);
    JavacOptions javacOptions = javacOptionsBuilder.build();

    AndroidLibraryGraphEnhancer graphEnhancer = new AndroidLibraryGraphEnhancer(
        params.getBuildTarget(),
        params.copyWithExtraDeps(
            Suppliers.ofInstance(resolver.getAllRules(args.exportedDeps.get()))),
        javacOptions,
        ResourceDependencyMode.TRANSITIVE);
    Optional<DummyRDotJava> dummyRDotJava = graphEnhancer.getBuildableForAndroidResources(
        resolver,
        /* createBuildableIfEmpty */ true);

    ImmutableSet<Path> additionalClasspathEntries = ImmutableSet.of();
    if (dummyRDotJava.isPresent()) {
      additionalClasspathEntries = ImmutableSet.of(dummyRDotJava.get().getRDotJavaBinFolder());
      ImmutableSortedSet<BuildRule> newExtraDeps = ImmutableSortedSet.<BuildRule>naturalOrder()
          .addAll(params.getExtraDeps().get())
          .add(dummyRDotJava.get())
          .build();
      params = params.copyWithExtraDeps(Suppliers.ofInstance(newExtraDeps));
    }

    JavaTestDescription.CxxLibraryEnhancement cxxLibraryEnhancement =
        new JavaTestDescription.CxxLibraryEnhancement(
            targetGraph,
            params,
            args.useCxxLibraries,
            pathResolver,
            cxxPlatform);
    params = cxxLibraryEnhancement.updatedParams;

    BuildTarget abiJarTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(CalculateAbi.FLAVOR)
            .build();

    RobolectricTest test =
        resolver.addToIndex(
            new RobolectricTest(
                params.appendExtraDeps(
                    Iterables.concat(
                        BuildRules.getExportedRules(
                            Iterables.concat(
                                params.getDeclaredDeps().get(),
                                resolver.getAllRules(args.providedDeps.get()))),
                        pathResolver.filterBuildRuleInputs(
                            javacOptions.getInputs(pathResolver)))),
                pathResolver,
                args.srcs.get(),
                JavaLibraryDescription.validateResources(
                    pathResolver,
                    args, params.getProjectFilesystem()),
                args.labels.get(),
                args.contacts.get(),
                args.proguardConfig.transform(
                    SourcePaths.toSourcePath(params.getProjectFilesystem())),
                new BuildTargetSourcePath(abiJarTarget),
                additionalClasspathEntries,
                javacOptions,
                vmArgs,
                cxxLibraryEnhancement.nativeLibsEnvironment,
                JavaTestDescription.validateAndGetSourcesUnderTest(
                    args.sourceUnderTest.get(),
                    params.getBuildTarget(),
                    resolver),
                args.resourcesRoot,
                args.mavenCoords,
                dummyRDotJava,
                testRuleTimeoutMs,
                args.getRunTestSeparately(),
                args.stdOutLogLevel,
                args.stdErrLogLevel,
                testTempDirOverride));

    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(test.getBuildTarget())));

    return test;
  }

  @SuppressFieldNotInitialized
  public class Arg extends JavaTestDescription.Arg {
  }
}
