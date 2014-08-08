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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxLinkableEnhancerTest {

  private static final Path DEFAULT_LINKER = Paths.get("linker");
  private static final Path DEFAULT_OUTPUT = Paths.get("libblah.a");
  private static final ImmutableList<SourcePath> DEFAULT_INPUTS = ImmutableList.<SourcePath>of(
      new TestSourcePath("a.o"),
      new TestSourcePath("b.o"),
      new TestSourcePath("c.o"));
  private static final ImmutableSortedSet<BuildRule> EMPTY_DEPS = ImmutableSortedSet.of();

  private static class FakeNativeLinkable extends FakeBuildRule implements NativeLinkable {

    private final NativeLinkableInput input;

    public FakeNativeLinkable(BuildRuleParams params, NativeLinkableInput input) {
      super(params);
      this.input = Preconditions.checkNotNull(input);
    }

    @Override
    public NativeLinkableInput getNativeLinkableInput() {
      return input;
    }

  }

  private static FakeNativeLinkable createNativeLinkable(
      String target,
      NativeLinkableInput nativeLinkableInput) {
    return new FakeNativeLinkable(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance(target)).build(),
        nativeLinkableInput);
  }

  @Test
  public void testThatBuildRuleSourcePathDepsAndPathsArePropagated() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    // Create a couple of genrules to generate inputs for an archive rule.
    Genrule genrule1 = GenruleBuilder
        .createGenrule(BuildTargetFactory.newInstance("//:genrule"))
        .setOut("foo/bar.o")
        .build();
    Genrule genrule2 = GenruleBuilder
        .createGenrule(BuildTargetFactory.newInstance("//:genrule2"))
        .setOut("foo/test.o")
        .build();

    // Build the archive using a normal input the outputs of the genrules above.
    CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        params,
        resolver,
        DEFAULT_LINKER,
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        target,
        DEFAULT_OUTPUT,
        ImmutableList.<SourcePath>of(
            new TestSourcePath("simple.o"),
            new BuildRuleSourcePath(genrule1),
            new BuildRuleSourcePath(genrule2)),
        EMPTY_DEPS);

    // Verify that the archive dependencies include the genrules providing the
    // SourcePath inputs.
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(genrule1, genrule2),
        cxxLink.getDeps());

    // Verify that the archive inputs are the outputs of the genrules.
    assertEquals(
        ImmutableSet.of(
            Paths.get("simple.o"),
            genrule1.getPathToOutputFile(),
            genrule2.getPathToOutputFile()),
        ImmutableSet.copyOf(
            cxxLink.getInputsToCompareToOutput()));
  }

  @Test
  public void testThatOriginalBuildParamsDepsDoNotPropagateToArchive() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    // Create an `Archive` rule using build params with an existing dependency,
    // as if coming from a `TargetNode` which had declared deps.  These should *not*
    // propagate to the `Archive` rule, since it only cares about dependencies generating
    // it's immediate inputs.
    BuildRule dep = new FakeBuildRule(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(
            BuildTargetFactory.newInstance("//:fake")));
    BuildTarget target = BuildTargetFactory.newInstance("//:archive");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dummy"))
            .setDeps(ImmutableSortedSet.of(dep))
            .build();
    CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        params,
        resolver,
        DEFAULT_LINKER,
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        target,
        DEFAULT_OUTPUT,
        DEFAULT_INPUTS,
        EMPTY_DEPS);

    // Verify that the archive rules dependencies are empty.
    assertEquals(cxxLink.getDeps(), ImmutableSortedSet.<BuildRule>of());
  }

  @Test
  public void testThatBuildTargetsFromNativeLinkableDepsContributeToActualDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    // Create a dummy build rule and add it to the resolver.
    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//:fake");
    FakeBuildRule fakeBuildRule = new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(fakeBuildTarget).build());
    resolver.addToIndex(fakeBuildRule);

    // Create a native linkable dep and have it list the fake build rule above as a link
    // time dependency.
    FakeNativeLinkable nativeLinkable = createNativeLinkable(
        "//:dep",
        new NativeLinkableInput(
            ImmutableSet.of(fakeBuildTarget),
            ImmutableList.<Path>of(),
            ImmutableList.<String>of()));

    // Construct a CxxLink object and pass the native linkable above as the dep.
    CxxLink cxxLink = CxxLinkableEnhancer.createCxxLinkableBuildRule(
        params,
        resolver,
        DEFAULT_LINKER,
        ImmutableList.<String>of(),
        ImmutableList.<String>of(),
        target,
        DEFAULT_OUTPUT,
        DEFAULT_INPUTS,
        ImmutableSortedSet.<BuildRule>of(nativeLinkable));

    // Verify that the fake build rule made it in as a dep.
    assertTrue(cxxLink.getDeps().contains(fakeBuildRule));
  }

}
