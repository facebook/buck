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

package com.facebook.buck.groovy;

import com.facebook.buck.java.CalculateAbi;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.TestType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;
import java.util.logging.Level;

public class GroovyTestDescription implements Description<GroovyTestDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("groovy_test");

  private final Optional<Long> testRuleTimeoutMs;
  private final Optional<Path> testTempDirOverride;

  public GroovyTestDescription(
      Optional<Long> testRuleTimeoutMs,
      Optional<Path> testTempDirOverride) {
    this.testRuleTimeoutMs = testRuleTimeoutMs;
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
  public <A extends Arg> GroovyTest createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    BuildTarget abiJarTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(CalculateAbi.FLAVOR)
            .build();

    final GroovyTest groovyTest =
        resolver.addToIndex(
            new GroovyTest(
                params,
                pathResolver,
                args.srcs.get(),
                GroovyLibraryDescription.validateResources(
                    pathResolver,
                    args,
                    params.getProjectFilesystem()),
                args.labels.get(),
                args.contacts.get(),
                new BuildTargetSourcePath(abiJarTarget),
                /* additionalClasspathEntries */ ImmutableSet.<Path>of(),
                TestType.JUNIT,
                args.vmArgs.get(),
                validateAndGetSourcesUnderTest(
                    args.sourceUnderTest.get(),
                    params.getBuildTarget(),
                    resolver),
                args.resourcesRoot,
                testRuleTimeoutMs,
                false,
                args.stdOutLogLevel,
                args.stdErrLogLevel,
                testTempDirOverride
            ));
    resolver.addToIndex(
        CalculateAbi.of(
            abiJarTarget,
            pathResolver,
            params,
            new BuildTargetSourcePath(groovyTest.getBuildTarget())));

    return groovyTest;
  }


  public static ImmutableSet<BuildRule> validateAndGetSourcesUnderTest(
      ImmutableSet<BuildTarget> sourceUnderTestTargets,
      BuildTarget owner,
      BuildRuleResolver resolver) {
    ImmutableSet.Builder<BuildRule> sourceUnderTest = ImmutableSet.builder();
    for (BuildTarget target : sourceUnderTestTargets) {
      BuildRule rule = resolver.getRule(target);
      if (rule == null) {
        throw new HumanReadableException(
            "Specified source under test for %s is not among its dependencies: %s",
            owner,
            target);
      }
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
  public static class Arg extends GroovyLibraryDescription.Arg {
    public Optional<ImmutableSortedSet<String>> contacts;
    public Optional<ImmutableSortedSet<BuildTarget>> sourceUnderTest;
    public Optional<ImmutableList<String>> vmArgs;
    public Optional<ImmutableSortedSet<Label>> labels;
    public Optional<Level> stdErrLogLevel;
    public Optional<Level> stdOutLogLevel;
  }
}
