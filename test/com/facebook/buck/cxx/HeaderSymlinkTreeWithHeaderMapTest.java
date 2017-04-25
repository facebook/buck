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

import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class HeaderSymlinkTreeWithHeaderMapTest {

  @Rule public final TemporaryPaths tmpDir = new TemporaryPaths();

  private ProjectFilesystem projectFilesystem;
  private BuildTarget buildTarget;
  private HeaderSymlinkTreeWithHeaderMap symlinkTreeBuildRule;
  private ImmutableMap<Path, SourcePath> links;
  private Path symlinkTreeRoot;
  private BuildRuleResolver ruleResolver;
  private SourcePathRuleFinder ruleFinder;
  private SourcePathResolver resolver;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = new FakeProjectFilesystem(tmpDir.getRoot());

    // Create a build target to use when building the symlink tree.
    buildTarget = BuildTargetFactory.newInstance("//test:test");

    // Get the first file we're symlinking
    Path link1 = Paths.get("file");
    Path file1 = tmpDir.newFile();
    Files.write(file1, "hello world".getBytes(Charsets.UTF_8));

    // Get the second file we're symlinking
    Path link2 = Paths.get("directory", "then", "file");
    Path file2 = tmpDir.newFile();
    Files.write(file2, "hello world".getBytes(Charsets.UTF_8));

    // Setup the map representing the link tree.
    links =
        ImmutableMap.of(
            link1,
            new PathSourcePath(projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file1)),
            link2,
            new PathSourcePath(projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file2)));

    // The output path used by the buildable for the link tree.
    symlinkTreeRoot =
        BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s/symlink-tree-root");

    ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(ruleResolver);
    resolver = new SourcePathResolver(ruleFinder);

    // Setup the symlink tree buildable.
    symlinkTreeBuildRule =
        HeaderSymlinkTreeWithHeaderMap.create(
            buildTarget, projectFilesystem, symlinkTreeRoot, links, ruleFinder);
  }

  @Test
  public void testSymlinkTreeBuildSteps() throws IOException {
    BuildContext buildContext = FakeBuildContext.withSourcePathResolver(resolver);
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    ImmutableList<Step> expectedBuildSteps =
        new ImmutableList.Builder<Step>()
            .addAll(MakeCleanDirectoryStep.of(projectFilesystem, symlinkTreeRoot))
            .add(
                new SymlinkTreeStep(
                    projectFilesystem, symlinkTreeRoot, resolver.getMappedPaths(links)))
            .add(
                new HeaderMapStep(
                    projectFilesystem,
                    HeaderSymlinkTreeWithHeaderMap.getPath(projectFilesystem, buildTarget),
                    ImmutableMap.of(
                        Paths.get("file"),
                        projectFilesystem
                            .getBuckPaths()
                            .getBuckOut()
                            .relativize(symlinkTreeRoot)
                            .resolve("file"),
                        Paths.get("directory/then/file"),
                        projectFilesystem
                            .getBuckPaths()
                            .getBuckOut()
                            .relativize(symlinkTreeRoot)
                            .resolve("directory/then/file"))))
            .build();
    ImmutableList<Step> actualBuildSteps =
        symlinkTreeBuildRule.getBuildSteps(buildContext, buildableContext);
    assertEquals(expectedBuildSteps, actualBuildSteps.subList(1, actualBuildSteps.size()));
  }

  @Test
  public void testSymlinkTreeRuleKeyChangesIfLinkMapChanges() throws Exception {
    Path aFile = tmpDir.newFile();
    Files.write(aFile, "hello world".getBytes(Charsets.UTF_8));
    HeaderSymlinkTreeWithHeaderMap modifiedSymlinkTreeBuildRule =
        HeaderSymlinkTreeWithHeaderMap.create(
            buildTarget,
            projectFilesystem,
            symlinkTreeRoot,
            ImmutableMap.of(
                Paths.get("different/link"),
                new PathSourcePath(
                    projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), aFile))),
            ruleFinder);

    // Calculate their rule keys and verify they're different.
    DefaultFileHashCache hashCache =
        DefaultFileHashCache.createDefaultFileHashCache(new ProjectFilesystem(tmpDir.getRoot()));
    FileHashLoader hashLoader = new StackedFileHashCache(ImmutableList.of(hashCache));
    RuleKey key1 =
        new DefaultRuleKeyFactory(0, hashLoader, resolver, ruleFinder).build(symlinkTreeBuildRule);
    RuleKey key2 =
        new DefaultRuleKeyFactory(0, hashLoader, resolver, ruleFinder)
            .build(modifiedSymlinkTreeBuildRule);
    assertNotEquals(key1, key2);
  }

  @Test
  public void testSymlinkTreeRuleKeyChangesIfLinkTargetsChange()
      throws InterruptedException, IOException {
    ruleResolver.addToIndex(symlinkTreeBuildRule);

    DefaultFileHashCache hashCache =
        DefaultFileHashCache.createDefaultFileHashCache(new ProjectFilesystem(tmpDir.getRoot()));
    FileHashLoader hashLoader = new StackedFileHashCache(ImmutableList.of(hashCache));
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(0, hashLoader, resolver, ruleFinder);

    // Calculate the rule key
    RuleKey key1 = ruleKeyFactory.build(symlinkTreeBuildRule);

    // Change the contents of the target of the link.
    Path existingFile = resolver.getAbsolutePath(links.values().asList().get(0));
    Files.write(existingFile, "something new".getBytes(Charsets.UTF_8));
    hashCache.invalidateAll();
    ruleKeyFactory = new DefaultRuleKeyFactory(0, hashLoader, resolver, ruleFinder);

    // Re-calculate the rule key
    RuleKey key2 = ruleKeyFactory.build(symlinkTreeBuildRule);

    // Verify that the rules keys are the same.
    assertNotEquals(key1, key2);
  }

  @Test
  public void testSymlinkTreeInputBasedRuleKeyDoesNotChangeIfLinkTargetsChange()
      throws IOException {
    ruleResolver.addToIndex(symlinkTreeBuildRule);

    InputBasedRuleKeyFactory ruleKeyFactory =
        new InputBasedRuleKeyFactory(
            0, FakeFileHashCache.createFromStrings(ImmutableMap.of()), resolver, ruleFinder);

    // Calculate the rule key
    RuleKey key1 = ruleKeyFactory.build(symlinkTreeBuildRule);

    // Change the contents of the target of the link.
    Path existingFile = resolver.getAbsolutePath(links.values().asList().get(0));
    Files.write(existingFile, "something new".getBytes(Charsets.UTF_8));
    ruleKeyFactory =
        new InputBasedRuleKeyFactory(
            0, FakeFileHashCache.createFromStrings(ImmutableMap.of()), resolver, ruleFinder);

    // Re-calculate the rule key
    RuleKey key2 = ruleKeyFactory.build(symlinkTreeBuildRule);

    // Verify that the rules keys are the same.
    assertEquals(key1, key2);
  }
}
