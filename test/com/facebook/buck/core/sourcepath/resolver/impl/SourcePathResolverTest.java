/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.sourcepath.resolver.impl;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.BuildTargetWithOutputs;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.PathReferenceRule;
import com.facebook.buck.core.rules.impl.PathReferenceRuleWithMultipleOutputs;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SourcePathResolverTest {
  @Rule public final ExpectedException exception = ExpectedException.none();

  @Test
  public void resolvePathSourcePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(new TestActionGraphBuilder()));
    Path expectedPath = Paths.get("foo");
    SourcePath sourcePath = PathSourcePath.of(projectFilesystem, expectedPath);

    assertEquals(expectedPath, pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void resolveDefaultBuildTargetSourcePathWithNoOutputLabel() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));
    Path expectedPath = Paths.get("foo");
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule = new PathReferenceRule(buildTarget, new FakeProjectFilesystem(), expectedPath);
    graphBuilder.addToIndex(rule);
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(rule.getBuildTarget());

    assertEquals(expectedPath, pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void resolveDefaultBuildTargetSourcePathWithOutputLabel() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    Path expectedPath = Paths.get("foo").resolve("bar");
    BuildRule rule =
        new PathReferenceRuleWithMultipleOutputs(
            buildTarget,
            new FakeProjectFilesystem(),
            null,
            ImmutableMap.of(OutputLabel.of("bar"), ImmutableSet.of(expectedPath)));
    graphBuilder.addToIndex(rule);
    BuildTargetWithOutputs buildTargetWithOutputs =
        BuildTargetWithOutputs.of(buildTarget, OutputLabel.of("bar"));
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(buildTargetWithOutputs);

    assertEquals(expectedPath, pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void shouldGetCorrectPathWhenMultipleOutputsAvailable() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    Path expectedPath = Paths.get("foo").resolve("bar");
    BuildRule rule =
        new PathReferenceRuleWithMultipleOutputs(
            buildTarget,
            new FakeProjectFilesystem(),
            null,
            ImmutableMap.of(
                OutputLabel.of("baz"),
                ImmutableSet.of(Paths.get("foo").resolve("baz")),
                OutputLabel.of("bar"),
                ImmutableSet.of(expectedPath),
                OutputLabel.of("qux"),
                ImmutableSet.of(Paths.get("foo").resolve("qux"))));
    graphBuilder.addToIndex(rule);
    BuildTargetWithOutputs buildTargetWithOutputs =
        BuildTargetWithOutputs.of(buildTarget, OutputLabel.of("bar"));
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(buildTargetWithOutputs);

    assertEquals(expectedPath, pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void cannotResolveBuildTargetWithNonExistentOutputLabel() {
    exception.expect(HumanReadableException.class);
    exception.expectMessage(containsString("No known output for: //:foo[baz]"));

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));
    Path path = Paths.get("foo").resolve("bar");
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule =
        new PathReferenceRuleWithMultipleOutputs(
            buildTarget,
            new FakeProjectFilesystem(),
            path,
            ImmutableMap.of(OutputLabel.of("bar"), ImmutableSet.of(path)));
    graphBuilder.addToIndex(rule);
    BuildTargetWithOutputs buildTargetWithOutputs =
        BuildTargetWithOutputs.of(buildTarget, OutputLabel.of("baz"));
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(buildTargetWithOutputs);

    pathResolver.getRelativePath(sourcePath);
  }

  @Test
  public void throwsWhenRequestTargetWithOutputLabelFromRuleThatDoesNotSupportMultipleOutputs() {
    exception.expect(IllegalStateException.class);
    exception.expectMessage(
        containsString(
            "Multiple outputs not supported for path_reference_rule target //:foo[bar]"));

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule =
        new PathReferenceRule(buildTarget, new FakeProjectFilesystem(), Paths.get("foo"));
    graphBuilder.addToIndex(rule);
    BuildTargetWithOutputs buildTargetWithOutputs =
        BuildTargetWithOutputs.of(buildTarget, OutputLabel.of("bar"));
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(buildTargetWithOutputs);

    pathResolver.getRelativePath(sourcePath);
  }

  @Test
  public void resolveExplicitBuildTargetSourcePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));
    Path expectedPath = Paths.get("foo");
    FakeBuildRule rule = new FakeBuildRule("//:foo");
    rule.setOutputFile("notfoo");
    graphBuilder.addToIndex(rule);
    SourcePath sourcePath = ExplicitBuildTargetSourcePath.of(rule.getBuildTarget(), expectedPath);

    assertEquals(expectedPath, pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void getRelativePathCanGetRelativePathOfPathSourcePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(new TestActionGraphBuilder()));
    Path expectedPath = Paths.get("foo");
    SourcePath sourcePath = PathSourcePath.of(projectFilesystem, expectedPath);

    assertEquals(expectedPath, pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void relativePathForADefaultBuildTargetSourcePathIsTheRulesOutputPath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));
    FakeBuildRule rule = new FakeBuildRule("//:foo");
    rule.setOutputFile("foo");
    graphBuilder.addToIndex(rule);
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(rule.getBuildTarget());

    assertEquals(rule.getOutputFile(), pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void testEmptyListAsInputToFilterInputsToCompareToOutput() {
    Iterable<SourcePath> sourcePaths = ImmutableSet.of();
    SourcePathResolverAdapter resolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(new TestActionGraphBuilder()));
    Iterable<Path> inputs = resolver.filterInputsToCompareToOutput(sourcePaths);
    MoreAsserts.assertIterablesEquals(ImmutableSet.<String>of(), inputs);
  }

  @Test
  public void testFilterInputsToCompareToOutputExcludesBuildTargetSourcePaths() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));
    FakeBuildRule rule =
        new FakeBuildRule(BuildTargetFactory.newInstance("//java/com/facebook:facebook"));
    graphBuilder.addToIndex(rule);

    Iterable<? extends SourcePath> sourcePaths =
        ImmutableSet.of(
            FakeSourcePath.of("java/com/facebook/Main.java"),
            FakeSourcePath.of("java/com/facebook/BuckConfig.java"),
            DefaultBuildTargetSourcePath.of(rule.getBuildTarget()),
            ExplicitBuildTargetSourcePath.of(rule.getBuildTarget(), Paths.get("foo")),
            ForwardingBuildTargetSourcePath.of(rule.getBuildTarget(), FakeSourcePath.of("bar")));
    Iterable<Path> inputs = pathResolver.filterInputsToCompareToOutput(sourcePaths);
    MoreAsserts.assertIterablesEquals(
        "Iteration order should be preserved: results should not be alpha-sorted.",
        ImmutableSet.of(
            Paths.get("java/com/facebook/Main.java"),
            Paths.get("java/com/facebook/BuckConfig.java")),
        inputs);
  }

  @Test
  public void getSourcePathNameOnPathSourcePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    String path = Paths.get("hello/world.txt").toString();

    // Test that constructing a PathSourcePath without an explicit name resolves to the
    // string representation of the path.
    PathSourcePath pathSourcePath1 = FakeSourcePath.of(projectFilesystem, path);
    SourcePathResolverAdapter resolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(new TestActionGraphBuilder()));
    String actual1 =
        resolver.getSourcePathName(BuildTargetFactory.newInstance("//:test"), pathSourcePath1);
    assertEquals(path, actual1);
  }

  @Test
  public void getSourcePathNameOnDefaultBuildTargetSourcePathWithDefaultLabel() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));

    // Verify that wrapping a genrule in a BuildTargetSourcePath resolves to the output name of
    // that genrule.
    String out = "test/blah.txt";
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setOut(out)
            .build(graphBuilder);
    DefaultBuildTargetSourcePath buildTargetSourcePath =
        DefaultBuildTargetSourcePath.of(genrule.getBuildTarget());
    String actual =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath);
    assertEquals(out, actual);
  }

  @Test
  public void getSourcePathNameOnDefaultBuildTargetSourcePathWithOutputLabel() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));

    String out = "test/blah.txt";
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setOuts(
                ImmutableMap.of("name", ImmutableSet.of(out), "other", ImmutableSet.of("wrong")))
            .build(graphBuilder);
    DefaultBuildTargetSourcePath buildTargetSourcePath =
        DefaultBuildTargetSourcePath.of(
            BuildTargetWithOutputs.of(genrule.getBuildTarget(), OutputLabel.of("name")));
    String actual =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath);
    assertEquals(out, actual);
  }

  @Test
  public void getSourcePathNameOnDefaultBuildTargetSourcePathForTargetWithoutOutputName() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));

    // Test that using other BuildRule types resolves to the short name.
    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//:fake");
    FakeBuildRule fakeBuildRule =
        new FakeBuildRule(
            fakeBuildTarget, new FakeProjectFilesystem(), TestBuildRuleParams.create());
    graphBuilder.addToIndex(fakeBuildRule);
    DefaultBuildTargetSourcePath buildTargetSourcePath =
        DefaultBuildTargetSourcePath.of(fakeBuildRule.getBuildTarget());
    String actual =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath);
    assertEquals(fakeBuildTarget.getShortName(), actual);
  }

  @Test
  public void getSourcePathNameOnExplicitBuildTargetSourcePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));

    // Verify that wrapping a genrule in a ExplicitBuildTargetSourcePath resolves to the output name
    // of that genrule.
    String out = "test/blah.txt";
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setOut(out)
            .build(graphBuilder);
    ExplicitBuildTargetSourcePath buildTargetSourcePath1 =
        ExplicitBuildTargetSourcePath.of(genrule.getBuildTarget(), Paths.get("foo"));
    String actual1 =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath1);
    assertEquals(out, actual1);

    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//package:fake");
    FakeBuildRule fakeBuildRule =
        new FakeBuildRule(
            fakeBuildTarget, new FakeProjectFilesystem(), TestBuildRuleParams.create());
    graphBuilder.addToIndex(fakeBuildRule);
    ExplicitBuildTargetSourcePath buildTargetSourcePath2 =
        ExplicitBuildTargetSourcePath.of(
            fakeBuildRule.getBuildTarget(),
            fakeBuildRule
                .getBuildTarget()
                .getCellRelativeBasePath()
                .getPath()
                .toPathDefaultFileSystem()
                .resolve("foo/bar"));
    String actual2 =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath2);
    assertEquals("fake", actual2);

    BuildTarget otherFakeBuildTarget = BuildTargetFactory.newInstance("//package:fake2");
    FakeBuildRule otherFakeBuildRule =
        new FakeBuildRule(
            otherFakeBuildTarget, new FakeProjectFilesystem(), TestBuildRuleParams.create());
    graphBuilder.addToIndex(otherFakeBuildRule);
    ExplicitBuildTargetSourcePath buildTargetSourcePath3 =
        ExplicitBuildTargetSourcePath.of(
            otherFakeBuildRule.getBuildTarget(),
            BuildTargetPaths.getGenPathForBaseName(
                    new FakeProjectFilesystem(), otherFakeBuildRule.getBuildTarget())
                .resolve("foo/bar"));
    String actual3 =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath3);
    assertEquals(Paths.get("foo", "bar").toString(), actual3);
  }

  @Test
  public void getSourcePathNameOnForwardingBuildTargetSourcePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));

    FakeBuildRule rule = new FakeBuildRule(BuildTargetFactory.newInstance("//package:baz"));
    graphBuilder.addToIndex(rule);

    // Verify that wrapping a genrule in a ForwardingBuildTargetSourcePath resolves to the output
    // name of that genrule.
    String out = "test/blah.txt";
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setOut(out)
            .build(graphBuilder);
    ForwardingBuildTargetSourcePath buildTargetSourcePath1 =
        ForwardingBuildTargetSourcePath.of(rule.getBuildTarget(), genrule.getSourcePathToOutput());
    String actual1 =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath1);
    assertEquals(out, actual1);

    ForwardingBuildTargetSourcePath buildTargetSourcePath2 =
        ForwardingBuildTargetSourcePath.of(rule.getBuildTarget(), FakeSourcePath.of("foo/bar"));
    String actual2 =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath2);
    assertEquals(Paths.get("foo", "bar").toString(), actual2);

    BuildTarget otherFakeBuildTarget = BuildTargetFactory.newInstance("//package2:fake2");
    FakeBuildRule otherFakeBuildRule =
        new FakeBuildRule(
            otherFakeBuildTarget, new FakeProjectFilesystem(), TestBuildRuleParams.create());
    graphBuilder.addToIndex(otherFakeBuildRule);
    ForwardingBuildTargetSourcePath buildTargetSourcePath3 =
        ForwardingBuildTargetSourcePath.of(
            otherFakeBuildRule.getBuildTarget(), buildTargetSourcePath2);
    String actual3 =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath3);
    assertEquals(Paths.get("foo", "bar").toString(), actual3);
  }

  @Test
  public void getSourcePathNameOnArchiveMemberSourcePath() {
    exception.expect(IllegalArgumentException.class);

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));

    String out = "test/blah.jar";
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setOut(out)
            .build(graphBuilder);
    SourcePath archiveSourcePath = genrule.getSourcePathToOutput();

    ArchiveMemberSourcePath archiveMemberSourcePath =
        ArchiveMemberSourcePath.of(archiveSourcePath, Paths.get("member"));

    pathResolver.getSourcePathName(null, archiveMemberSourcePath);
  }

  @Test
  public void getSourcePathNameExplicitPath() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));
    BuildRule rule = graphBuilder.addToIndex(new FakeBuildRule("//foo:bar"));
    assertThat(
        pathResolver.getSourcePathName(
            rule.getBuildTarget(),
            ExplicitBuildTargetSourcePath.of(
                rule.getBuildTarget(),
                BuildTargetPaths.getGenPathForBaseName(filesystem, rule.getBuildTarget())
                    .resolve("something.cpp"))),
        Matchers.equalTo("something.cpp"));
  }

  @Test
  public void getSourcePathNamesWithExplicitPathsAvoidesDuplicates() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(graphBuilder));
    BuildRule rule = graphBuilder.addToIndex(new FakeBuildRule("//foo:bar"));
    SourcePath sourcePath1 =
        ExplicitBuildTargetSourcePath.of(
            rule.getBuildTarget(),
            BuildTargetPaths.getGenPathForBaseName(filesystem, rule.getBuildTarget())
                .resolve("name1"));
    SourcePath sourcePath2 =
        ExplicitBuildTargetSourcePath.of(
            rule.getBuildTarget(),
            BuildTargetPaths.getGenPathForBaseName(filesystem, rule.getBuildTarget())
                .resolve("name2"));
    pathResolver.getSourcePathNames(
        rule.getBuildTarget(), "srcs", ImmutableSet.of(sourcePath1, sourcePath2));
  }

  @Test
  public void getRelativePathCanOnlyReturnARelativePath() {
    exception.expect(IllegalStateException.class);

    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathResolverAdapter pathResolver =
        new SourcePathResolverAdapter(DefaultSourcePathResolver.from(resolver));

    PathSourcePath path =
        FakeSourcePath.of(
            new FakeProjectFilesystem(), Paths.get("cheese").toAbsolutePath().toString());

    pathResolver.getRelativePath(path);
  }
}
