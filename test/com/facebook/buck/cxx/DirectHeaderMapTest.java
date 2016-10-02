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
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DirectHeaderMapTest {

  @Rule
  public final TemporaryPaths tmpDir = new TemporaryPaths();

  private ProjectFilesystem projectFilesystem;
  private BuildTarget buildTarget;
  private DirectHeaderMap buildRule;
  private ImmutableMap<Path, SourcePath> links;
  private Path symlinkTreeRoot;
  private Path headerMapPath;
  private Path file1;
  private Path file2;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = new FakeProjectFilesystem(tmpDir.getRoot());

    // Create a build target to use when building the symlink tree.
    buildTarget = BuildTargetFactory.newInstance("//test:test");

    // Get the first file we're symlinking
    Path link1 = Paths.get("file");
    file1 = tmpDir.newFile();
    Files.write(file1, "hello world".getBytes(Charsets.UTF_8));

    // Get the second file we're symlinking
    Path link2 = Paths.get("directory", "then", "file");
    file2 = tmpDir.newFile();
    Files.write(file2, "hello world".getBytes(Charsets.UTF_8));

    // Setup the map representing the link tree.
    links = ImmutableMap.of(
        link1,
        new PathSourcePath(
            projectFilesystem,
            MorePaths.relativize(tmpDir.getRoot(), file1)),
        link2,
        new PathSourcePath(
            projectFilesystem,
            MorePaths.relativize(tmpDir.getRoot(), file2)));

    // The output path used by the buildable for the link tree.
    symlinkTreeRoot = projectFilesystem.resolve(
        BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s/symlink-tree-root"));

    // Setup the symlink tree buildable.
    buildRule = new DirectHeaderMap(
        new FakeBuildRuleParamsBuilder(buildTarget).build(),
        new SourcePathResolver(
            new BuildRuleResolver(
              TargetGraph.EMPTY,
              new DefaultTargetNodeToBuildRuleTransformer())
        ),
        symlinkTreeRoot,
        links);

    headerMapPath = buildRule.getPathToOutput();
  }

  @Test
  public void testBuildSteps() throws IOException {
    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    ImmutableList<Step> expectedBuildSteps =
        ImmutableList.of(
            new MakeCleanDirectoryStep(filesystem, symlinkTreeRoot),
            new HeaderMapStep(
                filesystem,
                headerMapPath,
                ImmutableMap.of(
                    Paths.get("file"),
                    file1.toAbsolutePath(),
                    Paths.get("directory/then/file"),
                    file2.toAbsolutePath())));
    ImmutableList<Step> actualBuildSteps =
        buildRule.getBuildSteps(
            buildContext,
            buildableContext);
    assertEquals(expectedBuildSteps, actualBuildSteps.subList(1, actualBuildSteps.size()));
  }

  @Test
  public void testSymlinkTreeRuleKeyChangesIfLinkMapChanges() throws Exception {
    Path aFile = tmpDir.newFile();
    Files.write(aFile, "hello world".getBytes(Charsets.UTF_8));
    AbstractBuildRule modifiedBuildRule = new DirectHeaderMap(
        new FakeBuildRuleParamsBuilder(buildTarget).build(),
        new SourcePathResolver(
            new BuildRuleResolver(
              TargetGraph.EMPTY,
              new DefaultTargetNodeToBuildRuleTransformer())
        ),
        symlinkTreeRoot,
        ImmutableMap.of(
            Paths.get("different/link"),
            new PathSourcePath(
                projectFilesystem,
                MorePaths.relativize(tmpDir.getRoot(), aFile))));

    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
     );

    // Calculate their rule keys and verify they're different.
    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(
        ImmutableMap.of());
    RuleKey key1 = new DefaultRuleKeyBuilderFactory(0, hashCache, resolver).build(
        buildRule);
    RuleKey key2 = new DefaultRuleKeyBuilderFactory(0, hashCache, resolver).build(
        modifiedBuildRule);
    assertNotEquals(key1, key2);
  }

  @Test
  public void testRuleKeyDoesNotChangeIfLinkTargetsChange() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver(
        TargetGraph.EMPTY,
        new DefaultTargetNodeToBuildRuleTransformer());
    ruleResolver.addToIndex(buildRule);
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    DefaultRuleKeyBuilderFactory ruleKeyBuilderFactory = new DefaultRuleKeyBuilderFactory(
        0,
        FakeFileHashCache.createFromStrings(
            ImmutableMap.of()),
        resolver);

    // Calculate the rule key
    RuleKey key1 = ruleKeyBuilderFactory.build(buildRule);

    // Change the contents of the target of the link.
    Path existingFile =
        projectFilesystem.resolve(resolver.deprecatedGetPath(links.values().asList().get(0)));
    Files.write(existingFile, "something new".getBytes(Charsets.UTF_8));

    // Re-calculate the rule key
    RuleKey key2 = ruleKeyBuilderFactory.build(buildRule);

    // Verify that the rules keys are the same.
    assertEquals(key1, key2);
  }

}
