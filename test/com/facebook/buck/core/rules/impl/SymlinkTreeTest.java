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

package com.facebook.buck.core.rules.impl;

import static org.hamcrest.MatcherAssert.assertThat;
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
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestInputBasedRuleKeyFactory;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkMapsPaths;
import com.facebook.buck.step.fs.SymlinkTreeMergeStep;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.base.Charsets;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.hamcrest.junit.ExpectedException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SymlinkTreeTest {

  @Rule public final TemporaryPaths tmpDir = new TemporaryPaths();

  @Rule public final ExpectedException exception = ExpectedException.none();

  private ProjectFilesystem projectFilesystem;
  private BuildTarget buildTarget;
  private SymlinkTree symlinkTreeBuildRule;
  private ImmutableMap<Path, SourcePath> links;
  private Path outputPath;
  private SourcePathResolverAdapter pathResolver;
  private ActionGraphBuilder graphBuilder;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot());

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
            PathSourcePath.of(projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file1)),
            link2,
            PathSourcePath.of(projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file2)));

    // The output path used by the buildable for the link tree.
    outputPath =
        BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/symlink-tree-root");

    graphBuilder = new TestActionGraphBuilder();
    pathResolver = graphBuilder.getSourcePathResolver();

    // Setup the symlink tree buildable.
    symlinkTreeBuildRule =
        new SymlinkTree("link_tree", buildTarget, projectFilesystem, outputPath, links);
  }

  @Test
  public void testSymlinkTreeBuildSteps() {

    // Create the fake build contexts.
    BuildContext buildContext = FakeBuildContext.withSourcePathResolver(pathResolver);
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    // Verify the build steps are as expected.
    ImmutableList<Step> expectedBuildSteps =
        new ImmutableList.Builder<Step>()
            .addAll(
                MakeCleanDirectoryStep.of(
                    BuildCellRelativePath.fromCellRelativePath(
                        buildContext.getBuildCellRootPath(), projectFilesystem, outputPath)))
            .add(
                new SymlinkTreeMergeStep(
                    "link_tree",
                    projectFilesystem,
                    outputPath,
                    new SymlinkMapsPaths(pathResolver.getMappedPaths(links)),
                    (a, b) -> false))
            .build();
    ImmutableList<Step> actualBuildSteps =
        symlinkTreeBuildRule.getBuildSteps(buildContext, buildableContext);
    assertEquals(expectedBuildSteps, actualBuildSteps.subList(1, actualBuildSteps.size()));
  }

  @Test
  public void testSymlinkTreeRuleKeyChangesIfLinkMapChanges() throws Exception {
    // Create a BuildRule wrapping the stock SymlinkTree buildable.
    // BuildRule rule1 = symlinkTreeBuildable;

    // Also create a new BuildRule based around a SymlinkTree buildable with a different
    // link map.
    Path aFile = tmpDir.newFile();
    Files.write(aFile, "hello world".getBytes(Charsets.UTF_8));
    SymlinkTree modifiedSymlinkTreeBuildRule =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            ImmutableMap.of(
                Paths.get("different/link"),
                PathSourcePath.of(
                    projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), aFile))));
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();

    // Calculate their rule keys and verify they're different.
    DefaultFileHashCache hashCache =
        DefaultFileHashCache.createDefaultFileHashCache(
            TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot()),
            FileHashCacheMode.DEFAULT);
    FileHashLoader hashLoader = new StackedFileHashCache(ImmutableList.of(hashCache));
    RuleKey key1 =
        new TestDefaultRuleKeyFactory(hashLoader, ruleFinder).build(symlinkTreeBuildRule);
    RuleKey key2 =
        new TestDefaultRuleKeyFactory(hashLoader, ruleFinder).build(modifiedSymlinkTreeBuildRule);
    assertNotEquals(key1, key2);
  }

  @Test
  public void testSymlinkTreeRuleKeyDoesNotChangeIfLinkTargetsChangeOnUnix() throws IOException {
    graphBuilder.addToIndex(symlinkTreeBuildRule);

    InputBasedRuleKeyFactory ruleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            FakeFileHashCache.createFromStrings(ImmutableMap.of()), graphBuilder);

    // Calculate the rule key
    RuleKey key1 = ruleKeyFactory.build(symlinkTreeBuildRule);

    // Change the contents of the target of the link.
    Path existingFile = pathResolver.getAbsolutePath(links.values().asList().get(0));
    Files.write(existingFile, "something new".getBytes(Charsets.UTF_8));

    // Re-calculate the rule key
    RuleKey key2 = ruleKeyFactory.build(symlinkTreeBuildRule);

    // Verify that the rules keys are the same.
    assertEquals(key1, key2);
  }

  @Test
  public void testSymlinkTreeDependentRuleKeyChangesWhenLinkSourceContentChanges()
      throws Exception {
    // If a dependent of a symlink tree uses the symlink tree's output as an input, that dependent's
    // rulekey must change when the link contents change.
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    graphBuilder.addToIndex(symlinkTreeBuildRule);

    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableList.of(symlinkTreeBuildRule.getSourcePathToOutput()))
            .setOut("out")
            .build(graphBuilder);

    DefaultFileHashCache hashCache =
        DefaultFileHashCache.createDefaultFileHashCache(
            TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot()),
            FileHashCacheMode.DEFAULT);
    FileHashLoader hashLoader = new StackedFileHashCache(ImmutableList.of(hashCache));
    RuleKey ruleKey1 = new TestDefaultRuleKeyFactory(hashLoader, graphBuilder).build(genrule);

    Path existingFile =
        graphBuilder.getSourcePathResolver().getAbsolutePath(links.values().asList().get(0));
    Files.write(existingFile, "something new".getBytes(Charsets.UTF_8));
    hashCache.invalidateAll();

    RuleKey ruleKey2 = new TestDefaultRuleKeyFactory(hashLoader, graphBuilder).build(genrule);

    // Verify that the rules keys are different.
    assertNotEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void testSymlinkTreeInputBasedRuleKeysAreImmuneToLinkSourceContentChanges() {
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(graphBuilder);

    symlinkTreeBuildRule =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            ImmutableMap.of(Paths.get("link"), dep.getSourcePathToOutput()));

    // Generate an input-based rule key for the symlink tree with the contents of the link
    // target hashing to "aaaa".
    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(ImmutableMap.of("out", "aaaa"));
    InputBasedRuleKeyFactory inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(hashCache, graphBuilder);
    RuleKey ruleKey1 = inputBasedRuleKeyFactory.build(symlinkTreeBuildRule);

    // Generate an input-based rule key for the symlink tree with the contents of the link
    // target hashing to a different value: "bbbb".
    hashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of("out", "bbbb"));
    inputBasedRuleKeyFactory = new TestInputBasedRuleKeyFactory(hashCache, graphBuilder);
    RuleKey ruleKey2 = inputBasedRuleKeyFactory.build(symlinkTreeBuildRule);

    // Verify that the rules keys are the same.
    assertEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void verifyStepFailsIfKeyContainsDotDot() throws Exception {
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    SymlinkTree symlinkTree =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            ImmutableMap.of(
                Paths.get("../something"),
                PathSourcePath.of(
                    projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), tmpDir.newFile()))));
    int exitCode =
        symlinkTree
            .getVerifyStep(symlinkTree.getResolvedSymlinks(ruleResolver.getSourcePathResolver()))
            .execute(TestExecutionContext.newInstance())
            .getExitCode();
    assertThat(exitCode, Matchers.not(Matchers.equalTo(0)));
  }

  @Test
  public void resolveDuplicateRelativePathsIsNoopWhenThereAreNoDuplicates() {
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();

    ImmutableSortedSet<SourcePath> sourcePaths =
        ImmutableSortedSet.of(
            FakeSourcePath.of("one"), FakeSourcePath.of("two/two"), FakeSourcePath.of("three"));

    ImmutableBiMap<SourcePath, Path> resolvedDuplicates =
        SymlinkTree.resolveDuplicateRelativePaths(
            sourcePaths, ruleResolver.getSourcePathResolver());

    assertThat(
        resolvedDuplicates.inverse(),
        Matchers.equalTo(
            FluentIterable.from(sourcePaths)
                .uniqueIndex(ruleResolver.getSourcePathResolver()::getRelativePath)));
  }

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void resolveDuplicateRelativePaths() {
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    tmp.getRoot().resolve("one").toFile().mkdir();
    tmp.getRoot().resolve("two").toFile().mkdir();
    ProjectFilesystem fsOne =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().resolve("one"));
    ProjectFilesystem fsTwo =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().resolve("two"));

    ImmutableBiMap<SourcePath, Path> expected =
        ImmutableBiMap.of(
            FakeSourcePath.of(fsOne, "a/one.a"), Paths.get("a/one.a"),
            FakeSourcePath.of(fsOne, "a/two"), Paths.get("a/two"),
            FakeSourcePath.of(fsTwo, "a/one.a"), Paths.get("a/one-1.a"),
            FakeSourcePath.of(fsTwo, "a/two"), Paths.get("a/two-1"));

    ImmutableBiMap<SourcePath, Path> resolvedDuplicates =
        SymlinkTree.resolveDuplicateRelativePaths(
            ImmutableSortedSet.copyOf(expected.keySet()), ruleResolver.getSourcePathResolver());

    assertThat(resolvedDuplicates, Matchers.equalTo(expected));
  }

  @Test
  public void resolveDuplicateRelativePathsWithConflicts() {
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    tmp.getRoot().resolve("a-fs").toFile().mkdir();
    tmp.getRoot().resolve("b-fs").toFile().mkdir();
    tmp.getRoot().resolve("c-fs").toFile().mkdir();
    ProjectFilesystem fsOne =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().resolve("a-fs"));
    ProjectFilesystem fsTwo =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().resolve("b-fs"));
    ProjectFilesystem fsThree =
        TestProjectFilesystems.createProjectFilesystem(tmp.getRoot().resolve("c-fs"));

    ImmutableBiMap<SourcePath, Path> expected =
        ImmutableBiMap.<SourcePath, Path>builder()
            .put(FakeSourcePath.of(fsOne, "a/one.a"), Paths.get("a/one.a"))
            .put(FakeSourcePath.of(fsOne, "a/two"), Paths.get("a/two"))
            .put(FakeSourcePath.of(fsOne, "a/two-1"), Paths.get("a/two-1"))
            .put(FakeSourcePath.of(fsTwo, "a/one.a"), Paths.get("a/one-1.a"))
            .put(FakeSourcePath.of(fsTwo, "a/two"), Paths.get("a/two-2"))
            .put(FakeSourcePath.of(fsThree, "a/two"), Paths.get("a/two-3"))
            .build();

    ImmutableBiMap<SourcePath, Path> resolvedDuplicates =
        SymlinkTree.resolveDuplicateRelativePaths(
            ImmutableSortedSet.copyOf(expected.keySet()), ruleResolver.getSourcePathResolver());

    assertThat(resolvedDuplicates, Matchers.equalTo(expected));
  }

  @Test
  public void getsCorrectCompileTimeDeps() {
    assertEquals(ImmutableSortedSet.of(), symlinkTreeBuildRule.getBuildDeps());
  }
}
