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
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SymlinkTreeTest {

  @Rule
  public final TemporaryFolder tmpDir = new TemporaryFolder();

  private ProjectFilesystem projectFilesystem;
  private BuildTarget buildTarget;
  private SymlinkTree symlinkTreeBuildRule;
  private ImmutableMap<Path, SourcePath> links;
  private Path outputPath;

  @Before
  public void setUp() throws IOException {
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
    outputPath = projectFilesystem.resolve(
        BuildTargets.getGenPath(buildTarget, "%s/symlink-tree-root"));

    // Setup the symlink tree buildable.
    symlinkTreeBuildRule = new SymlinkTree(
        new FakeBuildRuleParamsBuilder(buildTarget).build(),
        new SourcePathResolver(new BuildRuleResolver()),
        outputPath,
        links);

  }

  @Test
  public void testSymlinkTreeBuildSteps() throws IOException {

    // Create the fake build contexts.
    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    // Verify the build steps are as expected.
    ImmutableList<Step> expectedBuildSteps = ImmutableList.of(
        new MakeCleanDirectoryStep(outputPath),
        new SymlinkTreeStep(
            outputPath,
            new SourcePathResolver(new BuildRuleResolver()).getMappedPaths(links)));
    ImmutableList<Step> actualBuildSteps = symlinkTreeBuildRule.getBuildSteps(
        buildContext,
        buildableContext);
    assertEquals(expectedBuildSteps, actualBuildSteps);

  }

  @Test
  public void testSymlinkTreeRuleKeyChangesIfLinkMapChanges() throws IOException {

    // Create a BuildRule wrapping the stock SymlinkTree buildable.
    //BuildRule rule1 = symlinkTreeBuildable;

    // Also create a new BuildRule based around a SymlinkTree buildable with a different
    // link map.
    Path aFile = tmpDir.newFile().toPath();
    Files.write(aFile, "hello world".getBytes(Charsets.UTF_8));
    AbstractBuildRule modifiedSymlinkTreeBuildRule = new SymlinkTree(
        new FakeBuildRuleParamsBuilder(buildTarget).build(),
        new SourcePathResolver(new BuildRuleResolver()),
        outputPath,
        ImmutableMap.<Path, SourcePath>of(
            Paths.get("different/link"),
            new PathSourcePath(
                projectFilesystem,
                MorePaths.relativize(tmpDir.getRoot().toPath(), aFile))));
    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver(ImmutableSet.of(
        symlinkTreeBuildRule,
        modifiedSymlinkTreeBuildRule)));

    // Calculate their rule keys and verify they're different.
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(FakeFileHashCache.createFromStrings(
            ImmutableMap.<String, String>of()));
    RuleKey.Builder builder1 = ruleKeyBuilderFactory.newInstance(
        symlinkTreeBuildRule,
        resolver);
    RuleKey.Builder builder2 = ruleKeyBuilderFactory.newInstance(
        modifiedSymlinkTreeBuildRule,
        resolver);
    symlinkTreeBuildRule.appendToRuleKey(builder1);
    modifiedSymlinkTreeBuildRule.appendToRuleKey(builder2);
    RuleKey.Builder.RuleKeyPair pair1 = builder1.build();
    RuleKey.Builder.RuleKeyPair pair2 = builder2.build();
    assertNotEquals(pair1.getTotalRuleKey(), pair2.getTotalRuleKey());
    assertNotEquals(pair1.getRuleKeyWithoutDeps(), pair2.getRuleKeyWithoutDeps());

  }

  @Test
  public void testSymlinkTreeRuleKeyDoesNotChangeIfLinkTargetsChangeOnUnix() throws IOException {

    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new FakeRuleKeyBuilderFactory(FakeFileHashCache.createFromStrings(
            ImmutableMap.<String, String>of()));

    SourcePathResolver resolver = new SourcePathResolver(new BuildRuleResolver(ImmutableSet.of(
        symlinkTreeBuildRule)));

    // Calculate the rule key
    RuleKey.Builder builder1 = ruleKeyBuilderFactory.newInstance(
        symlinkTreeBuildRule,
        resolver);
    symlinkTreeBuildRule.appendToRuleKey(builder1);
    RuleKey.Builder.RuleKeyPair pair1 = builder1.build();

    // Change the contents of the target of the link.
    Path existingFile =
        projectFilesystem.resolve(
            new SourcePathResolver(new BuildRuleResolver())
                .getPath(links.values().asList().get(0)));
    Files.write(existingFile, "something new".getBytes(Charsets.UTF_8));

    // Re-calculate the rule key
    RuleKey.Builder builder2 = ruleKeyBuilderFactory.newInstance(
        symlinkTreeBuildRule,
        resolver);
    symlinkTreeBuildRule.appendToRuleKey(builder2);
    RuleKey.Builder.RuleKeyPair pair2 = builder2.build();

    // Verify that the rules keys are the same.
    assertEquals(pair1.getTotalRuleKey(), pair2.getTotalRuleKey());
    assertEquals(pair1.getRuleKeyWithoutDeps(), pair2.getRuleKeyWithoutDeps());

  }

}
