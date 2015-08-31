/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.BuckConstant;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class HeaderSymlinkTreeWithHeaderMapTest {

  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();

  private ProjectFilesystem projectFilesystem;
  private BuildTarget buildTarget;
  private HeaderSymlinkTreeWithHeaderMap symlinkTreeBuildRule;
  private ImmutableMap<Path, SourcePath> links;
  private Path symlinkTreeRoot;
  private Path headerMapPath;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = new FakeProjectFilesystem(tmpDir.getRoot());

    // Create a build target to use when building the symlink tree.
    buildTarget = BuildTargetFactory.newInstance("//test:test");

    // Get the first file we're symlinking
    Path link1 = Paths.get("file");
    Path file1 = tmpDir.newFile().toPath();
    Files.write(file1, "hello world".getBytes(Charsets.UTF_8));

    // Get the second file we're symlinking
    Path link2 = Paths.get("directory", "then", "file");
    Path file2 = tmpDir.newFile().toPath();
    Files.write(file2, "hello world".getBytes(Charsets.UTF_8));

    // Setup the map representing the link tree.
    links = ImmutableMap.<Path, SourcePath>of(
        link1,
        new PathSourcePath(
            projectFilesystem,
            MorePaths.relativize(tmpDir.getRoot().toPath(), file1)),
        link2,
        new PathSourcePath(
            projectFilesystem,
            MorePaths.relativize(tmpDir.getRoot().toPath(), file2)));

    // The output path used by the buildable for the link tree.
    symlinkTreeRoot = BuildTargets.getGenPath(buildTarget, "%s/symlink-tree-root");
    headerMapPath = BuildTargets.getGenPath(buildTarget, "%s/symlink-tree-root.hmap");

    // Setup the symlink tree buildable.
    symlinkTreeBuildRule = new HeaderSymlinkTreeWithHeaderMap(
        new FakeBuildRuleParamsBuilder(buildTarget).build(),
        new SourcePathResolver(new BuildRuleResolver()),
        symlinkTreeRoot,
        headerMapPath,
        links);
  }

  @Test
  public void testSymlinkTreeBuildStepsAreEmpty() throws IOException {
    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    ImmutableList<Step> actualBuildSteps =
        symlinkTreeBuildRule.getBuildSteps(
            buildContext,
            buildableContext);
    assertThat(actualBuildSteps, Matchers.empty());
  }

  @Test
  public void testSymlinkTreePostBuildSteps() throws IOException {
    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    ImmutableList<Step> expectedBuildSteps =
        ImmutableList.of(
            new MakeCleanDirectoryStep(symlinkTreeRoot),
            new SymlinkTreeStep(
                symlinkTreeRoot,
                new SourcePathResolver(new BuildRuleResolver()).getMappedPaths(links)),
            new HeaderMapStep(
                headerMapPath,
                ImmutableMap.of(
                    Paths.get("file"),
                    BuckConstant.BUCK_OUTPUT_PATH
                        .relativize(symlinkTreeRoot)
                        .resolve("file"),
                    Paths.get("directory/then/file"),
                    BuckConstant.BUCK_OUTPUT_PATH
                        .relativize(symlinkTreeRoot)
                        .resolve("directory/then/file"))));
    ImmutableList<Step> actualBuildSteps =
        symlinkTreeBuildRule.getPostBuildSteps(
            buildContext,
            buildableContext);
    assertEquals(expectedBuildSteps, actualBuildSteps);
  }

  @Test
  public void testSymlinkTreeRuleKeyChangesIfLinkMapChanges() throws Exception {
    Path aFile = tmpDir.newFile().toPath();
    Files.write(aFile, "hello world".getBytes(Charsets.UTF_8));
    AbstractBuildRule modifiedSymlinkTreeBuildRule = new HeaderSymlinkTreeWithHeaderMap(
        new FakeBuildRuleParamsBuilder(buildTarget).build(),
        new SourcePathResolver(new BuildRuleResolver()),
        symlinkTreeRoot,
        headerMapPath,
        ImmutableMap.<Path, SourcePath>of(
            Paths.get("different/link"),
            new PathSourcePath(
                projectFilesystem,
                MorePaths.relativize(tmpDir.getRoot().toPath(), aFile))));
    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(
            ImmutableSet.of(
                symlinkTreeBuildRule,
                modifiedSymlinkTreeBuildRule)));

    // Calculate their rule keys and verify they're different.
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(ImmutableMap.<String, String>of()),
            resolver);
    RuleKeyBuilder builder1 = ruleKeyBuilderFactory.newInstance(
        symlinkTreeBuildRule);
    RuleKeyBuilder builder2 = ruleKeyBuilderFactory.newInstance(
        modifiedSymlinkTreeBuildRule);
    RuleKey pair1 = builder1.build();
    RuleKey pair2 = builder2.build();
    assertNotEquals(pair1, pair2);
  }

  @Test
  public void testSymlinkTreeRuleKeyDoesNotChangeIfLinkTargetsChange() throws IOException {
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver(ImmutableSet.of(
        symlinkTreeBuildRule)));

    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(ImmutableMap.<String, String>of()),
            resolver);

    // Calculate the rule key
    RuleKeyBuilder builder1 = ruleKeyBuilderFactory.newInstance(symlinkTreeBuildRule);
    RuleKey pair1 = builder1.build();

    // Change the contents of the target of the link.
    Path existingFile =
        projectFilesystem.resolve(
            new SourcePathResolver(new BuildRuleResolver())
                .getPath(links.values().asList().get(0)));
    Files.write(existingFile, "something new".getBytes(Charsets.UTF_8));

    // Re-calculate the rule key
    RuleKeyBuilder builder2 = ruleKeyBuilderFactory.newInstance(symlinkTreeBuildRule);
    RuleKey pair2 = builder2.build();

    // Verify that the rules keys are the same.
    assertEquals(pair1, pair2);
  }

}
