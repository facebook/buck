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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class SourcePathResolverTest {

  @Test
  public void resolvePathSourcePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    Path expectedPath = Paths.get("foo");
    SourcePath sourcePath = new PathSourcePath(projectFilesystem, expectedPath);

    assertEquals(expectedPath, pathResolver.getPath(sourcePath));
  }

  @Test
  public void resolveBuildTargetSourcePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    Path expectedPath = Paths.get("foo");
    BuildRule rule = new PathReferenceRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:foo")).build(),
        pathResolver,
        expectedPath);
    resolver.addToIndex(rule);
    SourcePath sourcePath = new BuildTargetSourcePath(projectFilesystem, rule.getBuildTarget());

    assertEquals(expectedPath, pathResolver.getPath(sourcePath));
  }

  @Test
  public void resolveBuildTargetSourcePathWithOverriddenOutputPath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    Path expectedPath = Paths.get("foo");
    BuildRule rule = new OutputOnlyBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:foo")).build(),
        pathResolver,
        Paths.get("notfoo"));
    resolver.addToIndex(rule);
    SourcePath sourcePath = new BuildTargetSourcePath(
        projectFilesystem,
        rule.getBuildTarget(),
        expectedPath);

    assertEquals(expectedPath, pathResolver.getPath(sourcePath));
  }

  @Test
  public void resolveMixedPaths() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    Path pathSourcePathExpectedPath = Paths.get("foo");
    Path buildTargetSourcePathExpectedPath = Paths.get("bar");
    Path buildRuleWithOverriddenOutputPathExpectedPath = Paths.get("baz");
    SourcePath pathSourcePath = new PathSourcePath(projectFilesystem, pathSourcePathExpectedPath);
    BuildRule rule = new PathReferenceRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:bar")).build(),
        pathResolver,
        buildTargetSourcePathExpectedPath);
    resolver.addToIndex(rule);
    SourcePath buildTargetSourcePath = new BuildTargetSourcePath(
        projectFilesystem,
        rule.getBuildTarget());
    BuildRule ruleWithOverriddenOutputPath = new PathReferenceRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:baz")).build(),
        pathResolver,
        Paths.get("notbaz"));
    resolver.addToIndex(ruleWithOverriddenOutputPath);
    SourcePath buildTargetSourcePathWithOverriddenOutputPath = new BuildTargetSourcePath(
        projectFilesystem,
        ruleWithOverriddenOutputPath.getBuildTarget(),
        buildRuleWithOverriddenOutputPathExpectedPath);

    assertEquals(
        ImmutableList.of(
            pathSourcePathExpectedPath,
            buildTargetSourcePathExpectedPath,
            buildRuleWithOverriddenOutputPathExpectedPath),
        pathResolver.getAllPaths(
            ImmutableList.of(
                pathSourcePath,
                buildTargetSourcePath,
                buildTargetSourcePathWithOverriddenOutputPath)));
  }

  @Test
  public void getRuleCanGetRuleOfBuildRuleSoucePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRule rule = new OutputOnlyBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:foo")).build(),
        pathResolver,
        Paths.get("foo"));
    resolver.addToIndex(rule);
    SourcePath sourcePath = new BuildTargetSourcePath(projectFilesystem, rule.getBuildTarget());

    assertEquals(Optional.of(rule), pathResolver.getRule(sourcePath));
  }

  @Test
  public void getRuleCannotGetRuleOfPathSoucePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    SourcePath sourcePath = new PathSourcePath(projectFilesystem, Paths.get("foo"));

    assertEquals(Optional.<BuildRule>absent(), pathResolver.getRule(sourcePath));
  }

  @Test
  public void getRelativePathCanGetRelativePathOfPathSourcePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    Path expectedPath = Paths.get("foo");
    SourcePath sourcePath = new PathSourcePath(projectFilesystem, expectedPath);

    assertEquals(Optional.of(expectedPath), pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void getRelativePathCannotGetRelativePathOfBuildTargetSourcePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildRule rule = new OutputOnlyBuildRule(
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:foo")).build(),
        pathResolver,
        Paths.get("foo"));
    resolver.addToIndex(rule);
    SourcePath sourcePath = new BuildTargetSourcePath(projectFilesystem, rule.getBuildTarget());

    assertEquals(Optional.<Path>absent(), pathResolver.getRelativePath(sourcePath));
  }

  @Test
  public void testEmptyListAsInputToFilterInputsToCompareToOutput() {
    Iterable<SourcePath> sourcePaths = ImmutableList.of();
    Iterable<Path> inputs = new SourcePathResolver(
        new BuildRuleResolver()).filterInputsToCompareToOutput(sourcePaths);
    MoreAsserts.assertIterablesEquals(ImmutableList.<String>of(), inputs);
  }

  @Test
  public void testFilterInputsToCompareToOutputExcludesBuildTargetSourcePaths() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeBuildRule rule = new FakeBuildRule(
        BuildRuleType.of("example"),
        BuildTargetFactory.newInstance("//java/com/facebook:facebook"),
        pathResolver);
    resolver.addToIndex(rule);

    Iterable<? extends SourcePath> sourcePaths = ImmutableList.of(
        new TestSourcePath("java/com/facebook/Main.java"),
        new TestSourcePath("java/com/facebook/BuckConfig.java"),
        new BuildTargetSourcePath(projectFilesystem, rule.getBuildTarget()));
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
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeBuildRule rule = new FakeBuildRule(
        BuildRuleType.of("example"),
        BuildTargetFactory.newInstance("//java/com/facebook:facebook"),
        pathResolver);
    resolver.addToIndex(rule);
    FakeBuildRule rule2 = new FakeBuildRule(
        BuildRuleType.of("foo"),
        BuildTargetFactory.newInstance("//bar:foo"),
        pathResolver);
    resolver.addToIndex(rule2);

    Iterable<? extends SourcePath> sourcePaths = ImmutableList.of(
        new TestSourcePath("java/com/facebook/Main.java"),
        new BuildTargetSourcePath(projectFilesystem, rule.getBuildTarget()),
        new TestSourcePath("java/com/facebook/BuckConfig.java"),
        new BuildTargetSourcePath(projectFilesystem, rule2.getBuildTarget()));
    Iterable<BuildRule> inputs = pathResolver.filterBuildRuleInputs(sourcePaths);
    MoreAsserts.assertIterablesEquals(
        "Iteration order should be preserved: results should not be alpha-sorted.",
        ImmutableList.of(
            rule,
            rule2),
        inputs);
  }

  @Test
  public void getSourcePathNameOnPathSourcePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Path path = Paths.get("hello/world.txt");

    // Test that constructing a PathSourcePath without an explicit name resolves to the
    // string representation of the path.
    PathSourcePath pathSourcePath1 = new PathSourcePath(projectFilesystem, path);
    String actual1 = new SourcePathResolver(new BuildRuleResolver()).getSourcePathName(
        BuildTargetFactory.newInstance("//:test"),
        pathSourcePath1);
    assertEquals(path.toString(), actual1);
  }

  @Test
  public void getSourcePathNameOnBuildTargetSourcePath() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    // Verify that wrapping a genrule in a BuildTargetSourcePath resolves to the output name of
    // that genrule.
    String out = "test/blah.txt";
    Genrule genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(BuildTargetFactory.newInstance("//:genrule"))
        .setOut(out)
        .build(resolver);
    BuildTargetSourcePath buildTargetSourcePath1 = new BuildTargetSourcePath(
        projectFilesystem,
        genrule.getBuildTarget());
    String actual1 = pathResolver.getSourcePathName(
        BuildTargetFactory.newInstance("//:test"),
        buildTargetSourcePath1);
    assertEquals(out, actual1);

    // Test that using other BuildRule types resolves to the short name.
    BuildTarget fakeBuildTarget = BuildTargetFactory.newInstance("//:fake");
    FakeBuildRule fakeBuildRule = new FakeBuildRule(
        new FakeBuildRuleParamsBuilder(fakeBuildTarget).build(),
        pathResolver);
    resolver.addToIndex(fakeBuildRule);
    BuildTargetSourcePath buildTargetSourcePath2 = new BuildTargetSourcePath(
        projectFilesystem,
        fakeBuildRule.getBuildTarget());
    String actual2 = pathResolver.getSourcePathName(
        BuildTargetFactory.newInstance("//:test"),
        buildTargetSourcePath2);
    assertEquals(fakeBuildTarget.getShortName(), actual2);
  }

  @Test
  public void getSourcePathNamesThrowsOnDuplicates() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    String parameter = "srcs";
    PathSourcePath pathSourcePath1 = new PathSourcePath(projectFilesystem, Paths.get("same_name"));
    PathSourcePath pathSourcePath2 = new PathSourcePath(projectFilesystem, Paths.get("same_name"));

    // Try to resolve these source paths, with the same name, together and verify that an
    // exception is thrown.
    try {
      new SourcePathResolver(new BuildRuleResolver()).getSourcePathNames(
          target,
          parameter,
          ImmutableList.<SourcePath>of(pathSourcePath1, pathSourcePath2));
      fail("expected to throw");
    } catch (HumanReadableException e) {
      assertTrue(e.getMessage().contains("duplicate entries"));
    }
  }

  private static class PathReferenceRule extends AbstractBuildRule {

    private final Path source;

    protected PathReferenceRule(
        BuildRuleParams buildRuleParams,
        SourcePathResolver resolver,
        Path source) {
      super(buildRuleParams, resolver);
      this.source = source;
    }

    @Override
    protected ImmutableCollection<Path> getInputsToCompareToOutput() {
      return ImmutableSet.of();
    }

    @Override
    protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
      return builder;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      return ImmutableList.of();
    }

    @Override
    public Path getPathToOutputFile() {
      return source;
    }
  }
}
