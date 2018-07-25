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

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestInputBasedRuleKeyFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.hashing.FileHashLoader;
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

public class DirectHeaderMapTest {

  @Rule public final TemporaryPaths tmpDir = new TemporaryPaths();

  private ProjectFilesystem projectFilesystem;
  private BuildTarget buildTarget;
  private DirectHeaderMap buildRule;
  private ActionGraphBuilder graphBuilder;
  private SourcePathResolver pathResolver;
  private ImmutableMap<Path, SourcePath> links;
  private Path symlinkTreeRoot;
  private Path headerMapPath;
  private Path file1;
  private Path file2;
  private SourcePathRuleFinder ruleFinder;

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
    links =
        ImmutableMap.of(
            link1,
            PathSourcePath.of(projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file1)),
            link2,
            PathSourcePath.of(projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file2)));

    // The output path used by the buildable for the link tree.
    symlinkTreeRoot =
        BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/symlink-tree-root");

    // Setup the symlink tree buildable.
    graphBuilder = new TestActionGraphBuilder();

    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    buildRule =
        new DirectHeaderMap(buildTarget, projectFilesystem, symlinkTreeRoot, links, ruleFinder);
    graphBuilder.addToIndex(buildRule);

    headerMapPath = pathResolver.getRelativePath(buildRule.getSourcePathToOutput());
  }

  @Test
  public void testBuildSteps() {
    BuildContext buildContext = FakeBuildContext.withSourcePathResolver(pathResolver);
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    ImmutableList<Step> expectedBuildSteps =
        ImmutableList.of(
            RmStep.of(
                    BuildCellRelativePath.fromCellRelativePath(
                        buildContext.getBuildCellRootPath(),
                        projectFilesystem,
                        buildRule.getRoot()))
                .withRecursive(true),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(), projectFilesystem, buildRule.getRoot())),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(),
                    projectFilesystem,
                    headerMapPath.getParent())),
            RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    buildContext.getBuildCellRootPath(), projectFilesystem, headerMapPath)),
            new HeaderMapStep(
                projectFilesystem,
                headerMapPath,
                ImmutableMap.of(
                    Paths.get("file"), file1,
                    Paths.get("directory/then/file"), file2)));
    ImmutableList<Step> actualBuildSteps = buildRule.getBuildSteps(buildContext, buildableContext);
    assertEquals(expectedBuildSteps, actualBuildSteps.subList(1, actualBuildSteps.size()));
  }

  @Test
  public void testSymlinkTreeRuleKeysChangeIfLinkMapChanges() throws Exception {
    Path aFile = tmpDir.newFile();
    Files.write(aFile, "hello world".getBytes(Charsets.UTF_8));
    ImmutableMap.Builder<Path, SourcePath> modifiedLinksBuilder = ImmutableMap.builder();
    for (Path p : links.keySet()) {
      modifiedLinksBuilder.put(tmpDir.getRoot().resolve("modified-" + p), links.get(p));
    }
    DirectHeaderMap modifiedBuildRule =
        new DirectHeaderMap(
            buildTarget,
            projectFilesystem,
            symlinkTreeRoot,
            modifiedLinksBuilder.build(),
            ruleFinder);

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);

    // Calculate their rule keys and verify they're different.
    FileHashLoader hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot()),
                    FileHashCacheMode.DEFAULT)));
    RuleKey key1 = new TestDefaultRuleKeyFactory(hashCache, resolver, ruleFinder).build(buildRule);
    RuleKey key2 =
        new TestDefaultRuleKeyFactory(hashCache, resolver, ruleFinder).build(modifiedBuildRule);
    assertNotEquals(key1, key2);

    key1 = new TestInputBasedRuleKeyFactory(hashCache, resolver, ruleFinder).build(buildRule);
    key2 =
        new TestInputBasedRuleKeyFactory(hashCache, resolver, ruleFinder).build(modifiedBuildRule);
    assertNotEquals(key1, key2);
  }

  @Test
  public void testRuleKeyDoesNotChangeIfLinkTargetsChange()
      throws InterruptedException, IOException {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(buildRule);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    // Calculate their rule keys and verify they're different.
    DefaultFileHashCache hashCache =
        DefaultFileHashCache.createDefaultFileHashCache(
            TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot()),
            FileHashCacheMode.DEFAULT);
    FileHashLoader hashLoader = new StackedFileHashCache(ImmutableList.of(hashCache));

    RuleKey defaultKey1 =
        new TestDefaultRuleKeyFactory(hashLoader, resolver, ruleFinder).build(buildRule);
    RuleKey inputKey1 =
        new TestInputBasedRuleKeyFactory(hashLoader, resolver, ruleFinder).build(buildRule);

    Files.write(file1, "hello other world".getBytes());
    hashCache.invalidateAll();

    RuleKey defaultKey2 =
        new TestDefaultRuleKeyFactory(hashLoader, resolver, ruleFinder).build(buildRule);
    RuleKey inputKey2 =
        new TestInputBasedRuleKeyFactory(hashLoader, resolver, ruleFinder).build(buildRule);

    // When the file content changes, the rulekey should change but the input rulekey should be
    // unchanged. This ensures that a dependent's rulekey changes correctly.
    assertNotEquals(defaultKey1, defaultKey2);
    assertEquals(inputKey1, inputKey2);
  }
}
