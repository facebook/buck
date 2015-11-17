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
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasSourceUnderTest;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.util.logging.Level;

public class JavaTestDescription implements Description<JavaTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("java_test");

  private final JavacOptions templateOptions;
  private final Optional<Long> defaultTestRuleTimeoutMs;
  private final CxxPlatform cxxPlatform;
  private final Optional<Path> testTempDirOverride;

  public JavaTestDescription(
      JavacOptions templateOptions,
      Optional<Long> defaultTestRuleTimeoutMs,
      CxxPlatform cxxPlatform,
      Optional<Path> testTempDirOverride) {
    this.templateOptions = templateOptions;
    this.defaultTestRuleTimeoutMs = defaultTestRuleTimeoutMs;
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
  public <A extends Arg> JavaTest createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

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

    CxxLibraryEnhancement cxxLibraryEnhancement = new CxxLibraryEnhancement(
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

    JavaTest test =
        resolver.addToIndex(
            new JavaTest(
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
                javacOptions.getGeneratedSourceFolderName(),
                args.labels.get(),
                args.contacts.get(),
                args.proguardConfig.transform(
                    SourcePaths.toSourcePath(params.getProjectFilesystem())),
                new BuildTargetSourcePath(abiJarTarget),
                /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
                args.testType.or(TestType.JUNIT),
                new JavacStepFactory(javacOptions, JavacOptionsAmender.IDENTITY),
                args.vmArgs.get(),
                cxxLibraryEnhancement.nativeLibsEnvironment,
                validateAndGetSourcesUnderTest(
                    args.sourceUnderTest.get(),
                    params.getBuildTarget(),
                    resolver),
                args.resourcesRoot,
                args.mavenCoords,
                args.testRuleTimeoutMs.or(defaultTestRuleTimeoutMs),
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

  public static ImmutableSet<BuildRule> validateAndGetSourcesUnderTest(
      ImmutableSet<BuildTarget> sourceUnderTestTargets,
      BuildTarget owner,
      BuildRuleResolver resolver) {
    ImmutableSet.Builder<BuildRule> sourceUnderTest = ImmutableSet.builder();
    for (BuildTarget target : sourceUnderTestTargets) {
      BuildRule rule = resolver.getRule(target);
      if (!(rule instanceof JavaLibrary)) {
        // In this case, the source under test specified in the build file was not a Java library
        // rule. Since EMMA requires the sources to be in Java, we will throw this exception and
        // not continue with the tests.
        throw new HumanReadableException(
            "Specified source under test for %s is not a Java library: %s (%s).",
            owner,
            rule.getFullyQualifiedName(),
            rule.getType());
      }
      sourceUnderTest.add(rule);
    }
    return sourceUnderTest.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends JavaLibraryDescription.Arg implements HasSourceUnderTest {
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<Label>> labels;
    @Hint(isDep = false) public Optional<ImmutableSortedSet<BuildTarget>> sourceUnderTest;
    public Optional<ImmutableList<String>> vmArgs;
    public Optional<TestType> testType;
    public Optional<Boolean> runTestSeparately;
    public Optional<Level> stdErrLogLevel;
    public Optional<Level> stdOutLogLevel;
    public Optional<String> pathToJavaAgent;
    public Optional<Boolean> useCxxLibraries;
    public Optional<Long> testRuleTimeoutMs;


    @Override
    public ImmutableSortedSet<BuildTarget> getSourceUnderTest() {
      return sourceUnderTest.get();
    }

    public boolean getRunTestSeparately() {
      return runTestSeparately.or(false);
    }
  }

  public static class CxxLibraryEnhancement {
    public final BuildRuleParams updatedParams;
    public final ImmutableMap<String, String> nativeLibsEnvironment;

    public CxxLibraryEnhancement(
        TargetGraph targetGraph,
        BuildRuleParams params,
        Optional<Boolean> useCxxLibraries,
        SourcePathResolver pathResolver,
        CxxPlatform cxxPlatform) {
      if (useCxxLibraries.or(false)) {
        SymlinkTree nativeLibsSymlinkTree =
            buildNativeLibsSymlinkTreeRule(targetGraph, params, pathResolver, cxxPlatform);
        updatedParams = params.appendExtraDeps(ImmutableList.<BuildRule>builder()
            .add(nativeLibsSymlinkTree)
            // Add all the native libraries as first-order dependencies.
            // This has two effects:
            // (1) They become runtime deps because JavaTest adds all first-order deps.
            // (2) They affect the JavaTest's RuleKey, so changing them will invalidate
            // the test results cache.
            .addAll(pathResolver.filterBuildRuleInputs(nativeLibsSymlinkTree.getLinks().values()))
            .build());
        nativeLibsEnvironment = ImmutableMap.of(
            cxxPlatform.getLd().searchPathEnvVar(), nativeLibsSymlinkTree.getRoot().toString());
      } else {
        updatedParams = params;
        nativeLibsEnvironment = ImmutableMap.of();
      }
    }

    public static SymlinkTree buildNativeLibsSymlinkTreeRule(
        TargetGraph targetGraph,
        BuildRuleParams buildRuleParams,
        SourcePathResolver pathResolver,
        CxxPlatform cxxPlatform) {
      return CxxDescriptionEnhancer.createSharedLibrarySymlinkTree(
          targetGraph,
          buildRuleParams,
          pathResolver,
          cxxPlatform,
          Predicates.or(
              Predicates.instanceOf(NativeLinkable.class),
              Predicates.instanceOf(JavaLibrary.class)));
    }
  }
}
