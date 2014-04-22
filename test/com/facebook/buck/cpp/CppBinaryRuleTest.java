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

package com.facebook.buck.cpp;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.DescribedRule;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

public class CppBinaryRuleTest {

  private BuildContext context = FakeBuildContext.NOOP_CONTEXT;
  private BuildableContext buildableContext = new FakeBuildableContext();
  private ExecutionContext executionContext = TestExecutionContext.newInstance();

  private BuildRule makeCppBinaryBuildRule(
      BuildTarget buildTarget,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet< BuildRule> deps) {
    CppBinaryDescription description = new CppBinaryDescription();
    CppBinaryDescription.Arg arg = description.createUnpopulatedConstructorArg();
    arg.srcs = srcs;
    arg.deps = Optional.of(deps);
    FakeBuildRuleParams buildRuleParams = new FakeBuildRuleParams(buildTarget, deps);
    CppBinary buildable = (CppBinary) description.createBuildable(buildRuleParams, arg);
    return new DescribedRule(description.getBuildRuleType(), buildable, buildRuleParams);
  }

  private BuildRule makeCppLibraryBuildRule(
      BuildTarget buildTarget,
      ImmutableSortedSet<SourcePath> srcs,
      ImmutableSortedSet<SourcePath> headers,
      ImmutableSortedSet< BuildRule> deps) {
    CppLibraryDescription description = new CppLibraryDescription();
    CppLibraryDescription.Arg arg = description.createUnpopulatedConstructorArg();
    arg.srcs = srcs;
    arg.deps = Optional.of(deps);
    arg.headers = headers;
    FakeBuildRuleParams buildRuleParams = new FakeBuildRuleParams(buildTarget, deps);
    CppLibrary buildable = (CppLibrary) description.createBuildable(buildRuleParams, arg);
    return new DescribedRule(description.getBuildRuleType(), buildable, buildRuleParams);
  }

  @Test
  public void testCppBinary() {
    BuildRule library = makeCppLibraryBuildRule(
        new BuildTarget("//lib", "bla"),
        ImmutableSortedSet.<SourcePath>of(
            new TestSourcePath("libsource1.c"),
            new TestSourcePath("libsource2.c")),
        ImmutableSortedSet.<SourcePath>of(),
        ImmutableSortedSet.<BuildRule>of());

    Buildable binary = makeCppBinaryBuildRule(
        new BuildTarget("//foo", "bar"),
        ImmutableSortedSet.<SourcePath>of(
          new TestSourcePath("source1.c"),
          new TestSourcePath("source2.c")),
          ImmutableSortedSet.<BuildRule>of(library)).getBuildable();

    assertThat(binary.getInputsToCompareToOutput(), hasSize(2));
    assertThat(
        binary.getInputsToCompareToOutput(),
        hasItems(
          Paths.get("source1.c"),
          Paths.get("source2.c")));

    List<Step> buildSteps = null;
    buildSteps = binary.getBuildSteps(context, buildableContext);
    assertNotNull(buildSteps);

    List<String> descriptions = Lists.transform(
        buildSteps, new Function<Step, String>() {
      @Override
      public String apply(Step input) {
        return input.getDescription(executionContext);
      }
    });

    assertThat(descriptions, hasSize(5));
    assertThat(descriptions, hasItems(
        "mkdir -p buck-out/bin/foo",
        "mkdir -p buck-out/gen",
        "g++ -c -I . source1.c -o buck-out/gen/source1.o",
        "g++ -c -I . source2.c -o buck-out/gen/source2.o",
        "g++ -I . buck-out/bin/lib/libbla.a buck-out/gen/source1.o " +
            "buck-out/gen/source2.o -o buck-out/bin/foo/bar"));
  }

  @Test
  public void testCppLibrary() {
    BuildRule library = makeCppLibraryBuildRule(
        new BuildTarget("//lib", "bla"),
        ImmutableSortedSet.<SourcePath>of(
            new TestSourcePath("libsource1.c"),
            new TestSourcePath("libsource2.c")),
        ImmutableSortedSet.<SourcePath>of(),
        ImmutableSortedSet.<BuildRule>of());

    Buildable targetLibrary = makeCppLibraryBuildRule(
        new BuildTarget("//foo", "bar"),
        ImmutableSortedSet.<SourcePath>of(
            new TestSourcePath("source1.c"),
            new TestSourcePath("source2.c")),
        ImmutableSortedSet.<SourcePath>of(
            new TestSourcePath("source.h")),
        ImmutableSortedSet.<BuildRule>of(library)).getBuildable();

    assertThat(targetLibrary.getInputsToCompareToOutput(), hasSize(3));
    assertThat(targetLibrary.getInputsToCompareToOutput(),
        hasItems(
            Paths.get("source1.c"),
            Paths.get("source2.c"),
            Paths.get("source.h")));

    List<Step> buildSteps = null;
    buildSteps = targetLibrary.getBuildSteps(context, buildableContext);
    assertNotNull(buildSteps);

    List<String> descriptions = Lists.transform(
        buildSteps, new Function<Step, String>() {
      @Override
      public String apply(Step input) {
        return input.getDescription(executionContext);
      }
    });

    assertThat(descriptions, hasSize(5));
    assertThat(descriptions, hasItems(
        "mkdir -p buck-out/bin/foo",
        "mkdir -p buck-out/gen",
        "g++ -c -I . source1.c -o buck-out/gen/source1.o",
        "g++ -c -I . source2.c -o buck-out/gen/source2.o",
        "ar -q buck-out/bin/foo/libbar.a buck-out/bin/lib/libbla.a " +
            "buck-out/gen/source1.o buck-out/gen/source2.o"));
  }
}
