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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class ArchiveTest {

  private static final Path AR = Paths.get("ar");
  private static final Archiver DEFAULT_ARCHIVER = new GnuArchiver(new HashedFileTool(AR));
  private static final Path RANLIB = Paths.get("ranlib");
  private static final Tool DEFAULT_RANLIB = new HashedFileTool(RANLIB);
  private static final Path DEFAULT_OUTPUT = Paths.get("foo/libblah.a");
  private static final ImmutableList<SourcePath> DEFAULT_INPUTS =
      ImmutableList.of(
          new FakeSourcePath("a.o"), new FakeSourcePath("b.o"), new FakeSourcePath("c.o"));

  @Test
  public void testThatInputChangesCauseRuleKeyChanges() {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(
            ImmutableMap.<String, String>builder()
                .put(AR.toString(), Strings.repeat("0", 40))
                .put(RANLIB.toString(), Strings.repeat("1", 40))
                .put("a.o", Strings.repeat("a", 40))
                .put("b.o", Strings.repeat("b", 40))
                .put("c.o", Strings.repeat("c", 40))
                .put(Paths.get("different").toString(), Strings.repeat("d", 40))
                .build());

    // Generate a rule key for the defaults.
    RuleKey defaultRuleKey =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                Archive.from(
                    target,
                    params,
                    ruleFinder,
                    DEFAULT_ARCHIVER,
                    ImmutableList.of(),
                    DEFAULT_RANLIB,
                    ImmutableList.of(),
                    Archive.Contents.NORMAL,
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUTS));

    // Verify that changing the archiver causes a rulekey change.
    RuleKey archiverChange =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                Archive.from(
                    target,
                    params,
                    ruleFinder,
                    new GnuArchiver(new HashedFileTool(Paths.get("different"))),
                    ImmutableList.of(),
                    DEFAULT_RANLIB,
                    ImmutableList.of(),
                    Archive.Contents.NORMAL,
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUTS));
    assertNotEquals(defaultRuleKey, archiverChange);

    // Verify that changing the output path causes a rulekey change.
    RuleKey outputChange =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                Archive.from(
                    target,
                    params,
                    ruleFinder,
                    DEFAULT_ARCHIVER,
                    ImmutableList.of(),
                    DEFAULT_RANLIB,
                    ImmutableList.of(),
                    Archive.Contents.NORMAL,
                    Paths.get("different"),
                    DEFAULT_INPUTS));
    assertNotEquals(defaultRuleKey, outputChange);

    // Verify that changing the inputs causes a rulekey change.
    RuleKey inputChange =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                Archive.from(
                    target,
                    params,
                    ruleFinder,
                    DEFAULT_ARCHIVER,
                    ImmutableList.of(),
                    DEFAULT_RANLIB,
                    ImmutableList.of(),
                    Archive.Contents.NORMAL,
                    DEFAULT_OUTPUT,
                    ImmutableList.of(new FakeSourcePath("different"))));
    assertNotEquals(defaultRuleKey, inputChange);

    // Verify that changing the type of archiver causes a rulekey change.
    RuleKey archiverTypeChange =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                Archive.from(
                    target,
                    params,
                    ruleFinder,
                    new BsdArchiver(new HashedFileTool(AR)),
                    ImmutableList.of(),
                    DEFAULT_RANLIB,
                    ImmutableList.of(),
                    Archive.Contents.NORMAL,
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUTS));
    assertNotEquals(defaultRuleKey, archiverTypeChange);
  }

  @Test
  public void flagsArePropagated() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    Archive archive =
        Archive.from(
            target,
            params,
            ruleFinder,
            DEFAULT_ARCHIVER,
            ImmutableList.of("-foo"),
            DEFAULT_RANLIB,
            ImmutableList.of("-bar"),
            Archive.Contents.NORMAL,
            DEFAULT_OUTPUT,
            ImmutableList.of(new FakeSourcePath("simple.o")));

    BuildContext buildContext =
        BuildContext.builder()
            .from(FakeBuildContext.NOOP_CONTEXT)
            .setSourcePathResolver(pathResolver)
            .build();

    ImmutableList<Step> steps = archive.getBuildSteps(buildContext, new FakeBuildableContext());
    Step archiveStep = FluentIterable.from(steps).filter(ArchiveStep.class).first().get();
    assertThat(
        archiveStep.getDescription(TestExecutionContext.newInstance()), containsString("-foo"));

    Step ranlibStep = FluentIterable.from(steps).filter(RanlibStep.class).first().get();
    assertThat(
        ranlibStep.getDescription(TestExecutionContext.newInstance()), containsString("-bar"));
  }

  @Test
  public void testThatBuildTargetSourcePathDepsAndPathsArePropagated() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();

    // Create a couple of genrules to generate inputs for an archive rule.
    Genrule genrule1 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setOut("foo/bar.o")
            .build(resolver);
    Genrule genrule2 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule2"))
            .setOut("foo/test.o")
            .build(resolver);

    // Build the archive using a normal input the outputs of the genrules above.
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    Archive archive =
        Archive.from(
            target,
            params,
            ruleFinder,
            DEFAULT_ARCHIVER,
            ImmutableList.of(),
            DEFAULT_RANLIB,
            ImmutableList.of(),
            Archive.Contents.NORMAL,
            DEFAULT_OUTPUT,
            ImmutableList.of(
                new FakeSourcePath("simple.o"),
                genrule1.getSourcePathToOutput(),
                genrule2.getSourcePathToOutput()));

    // Verify that the archive dependencies include the genrules providing the
    // SourcePath inputs.
    assertEquals(ImmutableSortedSet.<BuildRule>of(genrule1, genrule2), archive.getBuildDeps());
  }

  @Test
  public void testThatOriginalBuildParamsDepsDoNotPropagateToArchive() {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    // Create an `Archive` rule using build params with an existing dependency,
    // as if coming from a `TargetNode` which had declared deps.  These should *not*
    // propagate to the `Archive` rule, since it only cares about dependencies generating
    // it's immediate inputs.
    BuildRule dep =
        new FakeBuildRule(new FakeBuildRuleParamsBuilder("//:fake").build(), pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//:archive");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:dummy"))
            .setDeclaredDeps(ImmutableSortedSet.of(dep))
            .build();
    Archive archive =
        Archive.from(
            target,
            params,
            ruleFinder,
            DEFAULT_ARCHIVER,
            ImmutableList.of(),
            DEFAULT_RANLIB,
            ImmutableList.of(),
            Archive.Contents.NORMAL,
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS);

    // Verify that the archive rules dependencies are empty.
    assertEquals(archive.getBuildDeps(), ImmutableSortedSet.<BuildRule>of());
  }
}
