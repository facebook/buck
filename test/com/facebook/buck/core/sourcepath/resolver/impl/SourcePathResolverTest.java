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

package com.facebook.buck.core.sourcepath.resolver.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.SortedSet;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SourcePathResolverTest {
  @Rule public final ExpectedException exception = ExpectedException.none();

  @Test
  public void resolvePathSourcePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    Path expectedPath = Paths.get("foo");
    SourcePath sourcePath = PathSourcePath.of(projectFilesystem, expectedPath);

    assertEquals(expectedPath, pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void resolveDefaultBuildTargetSourcePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Path expectedPath = Paths.get("foo");
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:foo");
    BuildRule rule = new PathReferenceRule(buildTarget, new FakeProjectFilesystem(), expectedPath);
    graphBuilder.addToIndex(rule);
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(rule.getBuildTarget());

    assertEquals(expectedPath, pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void resolveExplicitBuildTargetSourcePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    Path expectedPath = Paths.get("foo");
    FakeBuildRule rule = new FakeBuildRule("//:foo");
    rule.setOutputFile("notfoo");
    graphBuilder.addToIndex(rule);
    SourcePath sourcePath = ExplicitBuildTargetSourcePath.of(rule.getBuildTarget(), expectedPath);

    assertEquals(expectedPath, pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void getRuleCanGetRuleOfBuildTargetSoucePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    FakeBuildRule rule = new FakeBuildRule("//:foo");
    rule.setOutputFile("foo");
    graphBuilder.addToIndex(rule);
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(rule.getBuildTarget());

    assertEquals(Optional.of(rule), ruleFinder.getRule(sourcePath));
  }

  @Test
  public void getRuleCannotGetRuleOfPathSoucePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePath sourcePath = PathSourcePath.of(projectFilesystem, Paths.get("foo"));

    assertEquals(Optional.empty(), ruleFinder.getRule(sourcePath));
  }

  @Test
  public void getRelativePathCanGetRelativePathOfPathSourcePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    Path expectedPath = Paths.get("foo");
    SourcePath sourcePath = PathSourcePath.of(projectFilesystem, expectedPath);

    assertEquals(expectedPath, pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void relativePathForADefaultBuildTargetSourcePathIsTheRulesOutputPath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    FakeBuildRule rule = new FakeBuildRule("//:foo");
    rule.setOutputFile("foo");
    graphBuilder.addToIndex(rule);
    SourcePath sourcePath = DefaultBuildTargetSourcePath.of(rule.getBuildTarget());

    assertEquals(rule.getOutputFile(), pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void testEmptyListAsInputToFilterInputsToCompareToOutput() {
    Iterable<SourcePath> sourcePaths = ImmutableList.of();
    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    Iterable<Path> inputs = resolver.filterInputsToCompareToOutput(sourcePaths);
    MoreAsserts.assertIterablesEquals(ImmutableList.<String>of(), inputs);
  }

  @Test
  public void testFilterInputsToCompareToOutputExcludesBuildTargetSourcePaths() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    FakeBuildRule rule =
        new FakeBuildRule(BuildTargetFactory.newInstance("//java/com/facebook:facebook"));
    graphBuilder.addToIndex(rule);

    Iterable<? extends SourcePath> sourcePaths =
        ImmutableList.of(
            FakeSourcePath.of("java/com/facebook/Main.java"),
            FakeSourcePath.of("java/com/facebook/BuckConfig.java"),
            DefaultBuildTargetSourcePath.of(rule.getBuildTarget()),
            ExplicitBuildTargetSourcePath.of(rule.getBuildTarget(), Paths.get("foo")),
            ForwardingBuildTargetSourcePath.of(rule.getBuildTarget(), FakeSourcePath.of("bar")));
    Iterable<Path> inputs = pathResolver.filterInputsToCompareToOutput(sourcePaths);
    MoreAsserts.assertIterablesEquals(
        "Iteration order should be preserved: results should not be alpha-sorted.",
        ImmutableList.of(
            Paths.get("java/com/facebook/Main.java"),
            Paths.get("java/com/facebook/BuckConfig.java")),
        inputs);
  }

  @Test
  public void testFilterBuildRuleInputsExcludesPathSourcePaths() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    FakeBuildRule rule =
        new FakeBuildRule(BuildTargetFactory.newInstance("//java/com/facebook:facebook"));
    graphBuilder.addToIndex(rule);
    FakeBuildRule rule2 = new FakeBuildRule(BuildTargetFactory.newInstance("//bar:foo"));
    graphBuilder.addToIndex(rule2);
    FakeBuildRule rule3 = new FakeBuildRule(BuildTargetFactory.newInstance("//bar:baz"));
    graphBuilder.addToIndex(rule3);

    Iterable<? extends SourcePath> sourcePaths =
        ImmutableList.of(
            FakeSourcePath.of("java/com/facebook/Main.java"),
            DefaultBuildTargetSourcePath.of(rule.getBuildTarget()),
            FakeSourcePath.of("java/com/facebook/BuckConfig.java"),
            ExplicitBuildTargetSourcePath.of(rule2.getBuildTarget(), Paths.get("foo")),
            ForwardingBuildTargetSourcePath.of(rule3.getBuildTarget(), FakeSourcePath.of("bar")));
    Iterable<BuildRule> inputs = ruleFinder.filterBuildRuleInputs(sourcePaths);
    MoreAsserts.assertIterablesEquals(
        "Iteration order should be preserved: results should not be alpha-sorted.",
        ImmutableList.of(rule, rule2, rule3),
        inputs);
  }

  @Test
  public void getSourcePathNameOnPathSourcePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    String path = Paths.get("hello/world.txt").toString();

    // Test that constructing a PathSourcePath without an explicit name resolves to the
    // string representation of the path.
    PathSourcePath pathSourcePath1 = FakeSourcePath.of(projectFilesystem, path);
    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
    String actual1 =
        resolver.getSourcePathName(BuildTargetFactory.newInstance("//:test"), pathSourcePath1);
    assertEquals(path, actual1);
  }

  @Test
  public void getSourcePathNameOnDefaultBuildTargetSourcePath() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    // Verify that wrapping a genrule in a BuildTargetSourcePath resolves to the output name of
    // that genrule.
    String out = "test/blah.txt";
    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
            .setOut(out)
            .build(graphBuilder);
    DefaultBuildTargetSourcePath buildTargetSourcePath1 =
        DefaultBuildTargetSourcePath.of(genrule.getBuildTarget());
    String actual1 =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath1);
    assertEquals(out, actual1);

    // Test that using other BuildRule types resolves to the short name.
    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//:fake");
    FakeBuildRule fakeBuildRule =
        new FakeBuildRule(
            fakeBuildTarget, new FakeProjectFilesystem(), TestBuildRuleParams.create());
    graphBuilder.addToIndex(fakeBuildRule);
    DefaultBuildTargetSourcePath buildTargetSourcePath2 =
        DefaultBuildTargetSourcePath.of(fakeBuildRule.getBuildTarget());
    String actual2 =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath2);
    assertEquals(fakeBuildTarget.getShortName(), actual2);
  }

  @Test
  public void getSourcePathNameOnExplicitBuildTargetSourcePath() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

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
            fakeBuildRule.getBuildTarget().getBasePath().resolve("foo/bar"));
    String actual2 =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath2);
    assertEquals(Paths.get("foo", "bar").toString(), actual2);

    BuildTarget otherFakeBuildTarget = BuildTargetFactory.newInstance("//package:fake2");
    FakeBuildRule otherFakeBuildRule =
        new FakeBuildRule(
            otherFakeBuildTarget, new FakeProjectFilesystem(), TestBuildRuleParams.create());
    graphBuilder.addToIndex(otherFakeBuildRule);
    ExplicitBuildTargetSourcePath buildTargetSourcePath3 =
        ExplicitBuildTargetSourcePath.of(
            otherFakeBuildRule.getBuildTarget(), Paths.get("buck-out/gen/package/foo/bar"));
    String actual3 =
        pathResolver.getSourcePathName(
            BuildTargetFactory.newInstance("//:test"), buildTargetSourcePath3);
    assertEquals(Paths.get("foo", "bar").toString(), actual3);
  }

  @Test
  public void getSourcePathNameOnForwardingBuildTargetSourcePath() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

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

  @Test(expected = IllegalArgumentException.class)
  public void getSourcePathNameOnArchiveMemberSourcePath() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

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
  public void getSourcePathNamesThrowsOnDuplicates() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    String parameter = "srcs";
    PathSourcePath pathSourcePath1 = FakeSourcePath.of(projectFilesystem, "same_name");
    PathSourcePath pathSourcePath2 = FakeSourcePath.of(projectFilesystem, "same_name");

    // Try to resolve these source paths, with the same name, together and verify that an
    // exception is thrown.
    try {
      SourcePathResolver resolver =
          DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));
      resolver.getSourcePathNames(
          target, parameter, ImmutableList.of(pathSourcePath1, pathSourcePath2));
      fail("expected to throw");
    } catch (HumanReadableException e) {
      assertTrue(e.getMessage().contains("duplicate entries"));
    }
  }

  @Test
  public void getSourcePathNameExplicitPath() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    BuildRule rule = graphBuilder.addToIndex(new FakeBuildRule("//foo:bar"));
    assertThat(
        pathResolver.getSourcePathName(
            rule.getBuildTarget(),
            ExplicitBuildTargetSourcePath.of(
                rule.getBuildTarget(),
                filesystem.getBuckPaths().getGenDir().resolve("foo").resolve("something.cpp"))),
        Matchers.equalTo("something.cpp"));
  }

  @Test
  public void getSourcePathNamesWithExplicitPathsAvoidesDuplicates() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    BuildRule rule = graphBuilder.addToIndex(new FakeBuildRule("//foo:bar"));
    SourcePath sourcePath1 =
        ExplicitBuildTargetSourcePath.of(
            rule.getBuildTarget(),
            filesystem.getBuckPaths().getGenDir().resolve("foo").resolve("name1"));
    SourcePath sourcePath2 =
        ExplicitBuildTargetSourcePath.of(
            rule.getBuildTarget(),
            filesystem.getBuckPaths().getGenDir().resolve("foo").resolve("name2"));
    pathResolver.getSourcePathNames(
        rule.getBuildTarget(), "srcs", ImmutableList.of(sourcePath1, sourcePath2));
  }

  @Test(expected = IllegalStateException.class)
  public void getRelativePathCanOnlyReturnARelativePath() {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    PathSourcePath path =
        FakeSourcePath.of(
            new FakeProjectFilesystem(), Paths.get("cheese").toAbsolutePath().toString());

    pathResolver.getRelativePath(path);
  }

  @Test
  public void testGetRelativePathForArchiveMemberSourcePath() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    BuildRule rule = graphBuilder.addToIndex(new FakeBuildRule("//foo:bar"));
    Path archivePath = filesystem.getBuckPaths().getGenDir().resolve("foo.jar");
    SourcePath archiveSourcePath =
        ExplicitBuildTargetSourcePath.of(rule.getBuildTarget(), archivePath);
    Path memberPath = Paths.get("foo.class");

    ArchiveMemberSourcePath path = ArchiveMemberSourcePath.of(archiveSourcePath, memberPath);

    ArchiveMemberPath relativePath = pathResolver.getRelativeArchiveMemberPath(path);
    assertEquals(archivePath, relativePath.getArchivePath());
    assertEquals(memberPath, relativePath.getMemberPath());
  }

  @Test
  public void testGetAbsolutePathForArchiveMemberSourcePath() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    BuildRule rule =
        graphBuilder.addToIndex(
            new FakeBuildRule(BuildTargetFactory.newInstance("//foo:bar"), filesystem));
    Path archivePath = filesystem.getBuckPaths().getGenDir().resolve("foo.jar");
    Path archiveAbsolutePath = filesystem.resolve(archivePath);
    SourcePath archiveSourcePath =
        ExplicitBuildTargetSourcePath.of(rule.getBuildTarget(), archivePath);
    Path memberPath = Paths.get("foo.class");

    ArchiveMemberSourcePath path = ArchiveMemberSourcePath.of(archiveSourcePath, memberPath);

    ArchiveMemberPath absolutePath = pathResolver.getAbsoluteArchiveMemberPath(path);
    assertEquals(archiveAbsolutePath, absolutePath.getArchivePath());
    assertEquals(memberPath, absolutePath.getMemberPath());
  }

  @Test
  public void getPathSourcePath() {
    BuildRuleResolver resolver = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    PathSourcePath pathSourcePath = FakeSourcePath.of(filesystem, "test");

    assertThat(
        pathResolver.getPathSourcePath(pathSourcePath),
        Matchers.equalTo(Optional.of(pathSourcePath)));

    assertThat(
        pathResolver.getPathSourcePath(
            DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:rule"))),
        Matchers.equalTo(Optional.empty()));

    assertThat(
        pathResolver.getPathSourcePath(
            ArchiveMemberSourcePath.of(pathSourcePath, filesystem.getPath("something"))),
        Matchers.equalTo(Optional.of(pathSourcePath)));
    assertThat(
        pathResolver.getPathSourcePath(
            ArchiveMemberSourcePath.of(
                DefaultBuildTargetSourcePath.of(BuildTargetFactory.newInstance("//:rule")),
                filesystem.getPath("something"))),
        Matchers.equalTo(Optional.empty()));
  }

  private static class PathReferenceRule extends AbstractBuildRule {

    private final Path source;

    protected PathReferenceRule(
        BuildTarget buildTarget, ProjectFilesystem projectFilesystem, Path source) {
      super(buildTarget, projectFilesystem);
      this.source = source;
    }

    @Override
    public SortedSet<BuildRule> getBuildDeps() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      return ImmutableList.of();
    }

    @Override
    public SourcePath getSourcePathToOutput() {
      return ExplicitBuildTargetSourcePath.of(getBuildTarget(), source);
    }
  }
}
