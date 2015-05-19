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
import com.facebook.buck.model.HasSourceUnderTest;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildRules;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;

public class JavaTestDescription implements Description<JavaTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("java_test");

  private final JavacOptions templateOptions;
  private final Optional<Long> testRuleTimeoutMs;

  public JavaTestDescription(
      JavacOptions templateOptions,
      Optional<Long> testRuleTimeoutMs) {
    this.templateOptions = templateOptions;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
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
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    JavacOptions.Builder javacOptionsBuilder =
        JavaLibraryDescription.getJavacOptions(
            resolver,
            args,
            templateOptions);
    AnnotationProcessingParams annotationParams =
        args.buildAnnotationProcessingParams(
            params.getBuildTarget(),
            params.getProjectFilesystem(),
            resolver);
    javacOptionsBuilder.setAnnotationProcessingParams(annotationParams);
    JavacOptions javacOptions = javacOptionsBuilder.build();

    return new JavaTest(
        params.appendExtraDeps(
            Iterables.concat(
                BuildRules.getExportedRules(
                    Iterables.concat(
                        params.getDeclaredDeps(),
                        resolver.getAllRules(args.providedDeps.get()))),
                pathResolver.filterBuildRuleInputs(javacOptions.getInputs()))),
        pathResolver,
        args.srcs.get(),
        JavaLibraryDescription.validateResources(
            pathResolver,
            args, params.getProjectFilesystem()),
        args.labels.get(),
        args.contacts.get(),
        args.proguardConfig.transform(SourcePaths.toSourcePath(params.getProjectFilesystem())),
        /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
        args.testType.or(TestType.JUNIT),
        javacOptions,
        args.vmArgs.get(),
        validateAndGetSourcesUnderTest(
            args.sourceUnderTest.get(),
            params.getBuildTarget(),
            resolver),
        args.resourcesRoot,
        testRuleTimeoutMs,
        args.getRunTestSeparately());
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
            rule.getType().getName());
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

    @Override
    public ImmutableSortedSet<BuildTarget> getSourceUnderTest() {
      return sourceUnderTest.get();
    }

    public boolean getRunTestSeparately() {
      return runTestSeparately.or(false);
    }
  }
}
