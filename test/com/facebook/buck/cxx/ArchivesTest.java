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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ArchivesTest {

  private static final Tool DEFAULT_ARCHIVER = new SourcePathTool(new TestSourcePath("ar"));
  private static final Path DEFAULT_OUTPUT = Paths.get("libblah.a");
  private static final ImmutableList<SourcePath> DEFAULT_INPUTS = ImmutableList.<SourcePath>of(
      new TestSourcePath("a.o"),
      new TestSourcePath("b.o"),
      new TestSourcePath("c.o"));

  @Test
  public void testThatBuildTargetSourcePathDepsAndPathsArePropagated() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    // Create a couple of genrules to generate inputs for an archive rule.
    Genrule genrule1 = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
        .setOut("foo/bar.o")
        .build(resolver);
    Genrule genrule2 = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule2"))
        .setOut("foo/test.o")
        .build(resolver);

    // Build the archive using a normal input the outputs of the genrules above.
    Archive archive = Archives.createArchiveRule(
        new SourcePathResolver(resolver),
        target,
        params,
        DEFAULT_ARCHIVER,
        DEFAULT_OUTPUT,
        ImmutableList.<SourcePath>of(
            new TestSourcePath("simple.o"),
            new BuildTargetSourcePath(genrule1.getBuildTarget()),
            new BuildTargetSourcePath(genrule2.getBuildTarget())));

    // Verify that the archive dependencies include the genrules providing the
    // SourcePath inputs.
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(genrule1, genrule2),
        archive.getDeps());

    // Verify that the archive inputs are the outputs of the genrules.
    assertEquals(
        ImmutableSet.of(Paths.get("simple.o")),
        ImmutableSet.copyOf(archive.getInputsToCompareToOutput()));
  }

  @Test
  public void testThatOriginalBuildParamsDepsDoNotPropagateToArchive() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());

    // Create an `Archive` rule using build params with an existing dependency,
    // as if coming from a `TargetNode` which had declared deps.  These should *not*
    // propagate to the `Archive` rule, since it only cares about dependencies generating
    // it's immediate inputs.
    BuildRule dep = new FakeBuildRule(
        BuildRuleParamsFactory.createTrivialBuildRuleParams(
            BuildTargetFactory.newInstance("//:fake")), pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:archive");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dummy"))
            .setDeps(ImmutableSortedSet.of(dep))
            .build();
    Archive archive = Archives.createArchiveRule(
        pathResolver,
        target,
        params,
        DEFAULT_ARCHIVER,
        DEFAULT_OUTPUT,
        DEFAULT_INPUTS);

    // Verify that the archive rules dependencies are empty.
    assertEquals(archive.getDeps(), ImmutableSortedSet.<BuildRule>of());
  }


}
