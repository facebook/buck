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

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.cxx.toolchain.ArchiveContents;
import com.facebook.buck.cxx.toolchain.Archiver;
import com.facebook.buck.cxx.toolchain.BsdArchiver;
import com.facebook.buck.cxx.toolchain.GnuArchiver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
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
import java.util.Optional;
import org.junit.Test;

public class ArchiveTest {
  private static final Path AR = Paths.get("ar");
  private static final Path RANLIB = Paths.get("ranlib");
  private static final Path DEFAULT_OUTPUT = Paths.get("foo/libblah.a");
  private static final ImmutableList<SourcePath> DEFAULT_INPUTS =
      ImmutableList.of(
          FakeSourcePath.of("a.o"), FakeSourcePath.of("b.o"), FakeSourcePath.of("c.o"));

  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private final Archiver DEFAULT_ARCHIVER =
      new GnuArchiver(new HashedFileTool(PathSourcePath.of(projectFilesystem, AR)));
  private final Optional<Tool> DEFAULT_RANLIB =
      Optional.of(new HashedFileTool(PathSourcePath.of(projectFilesystem, RANLIB)));

  @Test
  public void testThatInputChangesCauseRuleKeyChanges() {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
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
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                Archive.from(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    DEFAULT_ARCHIVER,
                    ImmutableList.of(),
                    DEFAULT_RANLIB,
                    ImmutableList.of(),
                    ArchiveContents.NORMAL,
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUTS,
                    /* cacheable */ true));

    // Verify that changing the archiver causes a rulekey change.
    RuleKey archiverChange =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                Archive.from(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new GnuArchiver(
                        new HashedFileTool(
                            PathSourcePath.of(projectFilesystem, Paths.get("different")))),
                    ImmutableList.of(),
                    DEFAULT_RANLIB,
                    ImmutableList.of(),
                    ArchiveContents.NORMAL,
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUTS,
                    /* cacheable */ true));
    assertNotEquals(defaultRuleKey, archiverChange);

    // Verify that changing the output path causes a rulekey change.
    RuleKey outputChange =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                Archive.from(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    DEFAULT_ARCHIVER,
                    ImmutableList.of(),
                    DEFAULT_RANLIB,
                    ImmutableList.of(),
                    ArchiveContents.NORMAL,
                    Paths.get("different"),
                    DEFAULT_INPUTS,
                    /* cacheable */ true));
    assertNotEquals(defaultRuleKey, outputChange);

    // Verify that changing the inputs causes a rulekey change.
    RuleKey inputChange =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                Archive.from(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    DEFAULT_ARCHIVER,
                    ImmutableList.of(),
                    DEFAULT_RANLIB,
                    ImmutableList.of(),
                    ArchiveContents.NORMAL,
                    DEFAULT_OUTPUT,
                    ImmutableList.of(FakeSourcePath.of("different")),
                    /* cacheable */ true));
    assertNotEquals(defaultRuleKey, inputChange);

    // Verify that changing the type of archiver causes a rulekey change.
    RuleKey archiverTypeChange =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                Archive.from(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new BsdArchiver(new HashedFileTool(PathSourcePath.of(projectFilesystem, AR))),
                    ImmutableList.of(),
                    DEFAULT_RANLIB,
                    ImmutableList.of(),
                    ArchiveContents.NORMAL,
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUTS,
                    /* cacheable */ true));
    assertNotEquals(defaultRuleKey, archiverTypeChange);
  }

  @Test
  public void flagsArePropagated() throws Exception {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    Archive archive =
        Archive.from(
            target,
            projectFilesystem,
            ruleFinder,
            DEFAULT_ARCHIVER,
            ImmutableList.of("-foo"),
            DEFAULT_RANLIB,
            ImmutableList.of("-bar"),
            ArchiveContents.NORMAL,
            DEFAULT_OUTPUT,
            ImmutableList.of(FakeSourcePath.of("simple.o")),
            /* cacheable */ true);

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
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    // Create a couple of genrules to generate inputs for an archive rule.
    Genrule genrule1 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setOut("foo/bar.o")
            .build(graphBuilder);
    Genrule genrule2 =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule2"))
            .setOut("foo/test.o")
            .build(graphBuilder);

    // Build the archive using a normal input the outputs of the genrules above.
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    Archive archive =
        Archive.from(
            target,
            projectFilesystem,
            ruleFinder,
            DEFAULT_ARCHIVER,
            ImmutableList.of(),
            DEFAULT_RANLIB,
            ImmutableList.of(),
            ArchiveContents.NORMAL,
            DEFAULT_OUTPUT,
            ImmutableList.of(
                FakeSourcePath.of("simple.o"),
                genrule1.getSourcePathToOutput(),
                genrule2.getSourcePathToOutput()),
            /* cacheable */ true);

    // Verify that the archive dependencies include the genrules providing the
    // SourcePath inputs.
    assertEquals(ImmutableSortedSet.<BuildRule>of(genrule1, genrule2), archive.getBuildDeps());
  }
}
