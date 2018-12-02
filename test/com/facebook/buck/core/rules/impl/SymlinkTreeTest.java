/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.core.rules.impl;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

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
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
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
import com.facebook.buck.step.fs.SymlinkTreeMergeStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.base.Charsets;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
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
  private SourcePathRuleFinder ruleFinder;
  private SourcePathResolver pathResolver;
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
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    // Setup the symlink tree buildable.
    symlinkTreeBuildRule =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            links,
            ImmutableMultimap.of(),
            ruleFinder);
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
                new SymlinkTreeStep(
                    "link_tree", projectFilesystem, outputPath, pathResolver.getMappedPaths(links)))
            .add(
                new SymlinkTreeMergeStep(
                    "link_tree", projectFilesystem, outputPath, ImmutableMultimap.of()))
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
                    projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), aFile))),
            ImmutableMultimap.of(),
            ruleFinder);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);

    // Calculate their rule keys and verify they're different.
    DefaultFileHashCache hashCache =
        DefaultFileHashCache.createDefaultFileHashCache(
            TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot()),
            FileHashCacheMode.DEFAULT);
    FileHashLoader hashLoader = new StackedFileHashCache(ImmutableList.of(hashCache));
    RuleKey key1 =
        new TestDefaultRuleKeyFactory(hashLoader, resolver, ruleFinder).build(symlinkTreeBuildRule);
    RuleKey key2 =
        new TestDefaultRuleKeyFactory(hashLoader, resolver, ruleFinder)
            .build(modifiedSymlinkTreeBuildRule);
    assertNotEquals(key1, key2);
  }

  @Test
  public void testSymlinkTreeRuleKeyChangesIfMergeDirectoriesChange() throws Exception {
    // Create a BuildRule wrapping the stock SymlinkTree buildable.
    // BuildRule rule1 = symlinkTreeBuildable;

    Path file1 = tmpDir.newFile();
    Files.write(file1, "hello world".getBytes(Charsets.UTF_8));

    BuildTarget exportFileTarget1 = BuildTargetFactory.newInstance("//test:dir1");
    BuildTarget exportFileTarget2 = BuildTargetFactory.newInstance("//test:dir2");

    Genrule exportFile1 =
        GenruleBuilder.newGenruleBuilder(exportFileTarget1).setOut("dir1").build(graphBuilder);
    Genrule exportFile2 =
        GenruleBuilder.newGenruleBuilder(exportFileTarget2).setOut("dir1").build(graphBuilder);

    projectFilesystem.mkdirs(Paths.get("test", "dir1"));
    projectFilesystem.writeContentsToPath("file", Paths.get("test", "dir1", "file1"));
    projectFilesystem.mkdirs(Paths.get("test", "dir2"));
    projectFilesystem.writeContentsToPath("file", Paths.get("test", "dir2", "file2"));
    graphBuilder.computeIfAbsent(exportFileTarget1, target -> exportFile1);
    graphBuilder.computeIfAbsent(exportFileTarget2, target -> exportFile2);

    // Create three link tree objects. One will change the dependencies, and one just changes
    // destination subdirs to make sure that's taken into account
    SymlinkTree firstSymLinkTreeBuildRule =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            ImmutableMap.of(
                Paths.get("file"),
                PathSourcePath.of(
                    projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file1))),
            ImmutableSetMultimap.of(Paths.get(""), exportFile1.getSourcePathToOutput()),
            ruleFinder);

    SymlinkTree secondSymLinkTreeBuildRule =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            ImmutableMap.of(
                Paths.get("file"),
                PathSourcePath.of(
                    projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file1))),
            ImmutableSetMultimap.of(Paths.get(""), exportFile2.getSourcePathToOutput()),
            ruleFinder);

    SymlinkTree thirdSymLinkTreeBuildRule =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            ImmutableMap.of(
                Paths.get("file"),
                PathSourcePath.of(
                    projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file1))),
            ImmutableSetMultimap.of(Paths.get("other_subdir"), exportFile2.getSourcePathToOutput()),
            ruleFinder);

    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);

    // Calculate their rule keys and verify they're different.
    DefaultFileHashCache hashCache =
        DefaultFileHashCache.createDefaultFileHashCache(
            TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot()),
            FileHashCacheMode.DEFAULT);
    FileHashLoader hashLoader = new StackedFileHashCache(ImmutableList.of(hashCache));
    RuleKey key1 =
        new TestDefaultRuleKeyFactory(hashLoader, resolver, ruleFinder)
            .build(firstSymLinkTreeBuildRule);
    RuleKey key2 =
        new TestDefaultRuleKeyFactory(hashLoader, resolver, ruleFinder)
            .build(secondSymLinkTreeBuildRule);
    RuleKey key3 =
        new TestDefaultRuleKeyFactory(hashLoader, resolver, ruleFinder)
            .build(thirdSymLinkTreeBuildRule);

    assertNotEquals(key1, key2);
    assertNotEquals(key1, key3);
    assertNotEquals(key2, key3);
  }

  @Test
  public void testSymlinkTreeRuleKeyAreEqualIfMergeDirectoriesAreEquivalent() throws Exception {
    // Create a BuildRule wrapping the stock SymlinkTree buildable.
    // BuildRule rule1 = symlinkTreeBuildable;

    Path file1 = tmpDir.newFile();
    Files.write(file1, "hello world".getBytes(Charsets.UTF_8));

    BuildTarget exportFileTarget1 = BuildTargetFactory.newInstance("//test:dir1");
    BuildTarget exportFileTarget2 = BuildTargetFactory.newInstance("//test:dir2");

    Genrule exportFile1 =
        GenruleBuilder.newGenruleBuilder(exportFileTarget1).setOut("dir1").build(graphBuilder);
    Genrule exportFile2 =
        GenruleBuilder.newGenruleBuilder(exportFileTarget2).setOut("dir1").build(graphBuilder);

    projectFilesystem.mkdirs(Paths.get("test", "dir1"));
    projectFilesystem.writeContentsToPath("file", Paths.get("test", "dir1", "file1"));
    projectFilesystem.mkdirs(Paths.get("test", "dir2"));
    projectFilesystem.writeContentsToPath("file", Paths.get("test", "dir2", "file2"));
    graphBuilder.computeIfAbsent(exportFileTarget1, target -> exportFile1);
    graphBuilder.computeIfAbsent(exportFileTarget2, target -> exportFile2);

    ImmutableListMultimap<Path, SourcePath> firstMergeDirectories =
        ImmutableListMultimap.of(
            Paths.get(""),
            exportFile2.getSourcePathToOutput(),
            Paths.get(""),
            exportFile1.getSourcePathToOutput(),
            Paths.get("subdir"),
            exportFile2.getSourcePathToOutput(),
            Paths.get("subdir"),
            exportFile1.getSourcePathToOutput());

    ImmutableListMultimap<Path, SourcePath> secondMergeDirectories =
        ImmutableListMultimap.of(
            Paths.get("subdir"),
            exportFile1.getSourcePathToOutput(),
            Paths.get("subdir"),
            exportFile2.getSourcePathToOutput(),
            Paths.get(""),
            exportFile1.getSourcePathToOutput(),
            Paths.get(""),
            exportFile2.getSourcePathToOutput());

    // Create three link tree objects. One will change the dependencies, and one just changes
    // destination subdirs to make sure that's taken into account
    SymlinkTree firstSymLinkTreeBuildRule =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            ImmutableMap.of(
                Paths.get("file"),
                PathSourcePath.of(
                    projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file1))),
            firstMergeDirectories,
            ruleFinder);

    SymlinkTree secondSymLinkTreeBuildRule =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            ImmutableMap.of(
                Paths.get("file"),
                PathSourcePath.of(
                    projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), file1))),
            secondMergeDirectories,
            ruleFinder);

    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);

    // Calculate their rule keys and verify they're different.
    DefaultFileHashCache hashCache =
        DefaultFileHashCache.createDefaultFileHashCache(
            TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot()),
            FileHashCacheMode.DEFAULT);
    FileHashLoader hashLoader = new StackedFileHashCache(ImmutableList.of(hashCache));
    RuleKey key1 =
        new TestDefaultRuleKeyFactory(hashLoader, resolver, ruleFinder)
            .build(firstSymLinkTreeBuildRule);
    RuleKey key2 =
        new TestDefaultRuleKeyFactory(hashLoader, resolver, ruleFinder)
            .build(secondSymLinkTreeBuildRule);

    assertNotEquals(firstMergeDirectories, secondMergeDirectories);
    assertEquals(key1, key2);
  }

  @Test
  public void testSymlinkTreeRuleKeyDoesNotChangeIfLinkTargetsChangeOnUnix() throws IOException {
    graphBuilder.addToIndex(symlinkTreeBuildRule);

    InputBasedRuleKeyFactory ruleKeyFactory =
        new TestInputBasedRuleKeyFactory(
            FakeFileHashCache.createFromStrings(ImmutableMap.of()), pathResolver, ruleFinder);

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
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

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
    RuleKey ruleKey1 =
        new TestDefaultRuleKeyFactory(hashLoader, pathResolver, ruleFinder).build(genrule);

    Path existingFile = pathResolver.getAbsolutePath(links.values().asList().get(0));
    Files.write(existingFile, "something new".getBytes(Charsets.UTF_8));
    hashCache.invalidateAll();

    RuleKey ruleKey2 =
        new TestDefaultRuleKeyFactory(hashLoader, pathResolver, ruleFinder).build(genrule);

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
            ImmutableMap.of(Paths.get("link"), dep.getSourcePathToOutput()),
            ImmutableMultimap.of(),
            ruleFinder);

    // Generate an input-based rule key for the symlink tree with the contents of the link
    // target hashing to "aaaa".
    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(ImmutableMap.of("out", "aaaa"));
    InputBasedRuleKeyFactory inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder);
    RuleKey ruleKey1 = inputBasedRuleKeyFactory.build(symlinkTreeBuildRule);

    // Generate an input-based rule key for the symlink tree with the contents of the link
    // target hashing to a different value: "bbbb".
    hashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of("out", "bbbb"));
    inputBasedRuleKeyFactory =
        new TestInputBasedRuleKeyFactory(hashCache, pathResolver, ruleFinder);
    RuleKey ruleKey2 = inputBasedRuleKeyFactory.build(symlinkTreeBuildRule);

    // Verify that the rules keys are the same.
    assertEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void verifyStepFailsIfKeyContainsDotDot() throws Exception {
    SymlinkTree symlinkTree =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            ImmutableMap.of(
                Paths.get("../something"),
                PathSourcePath.of(
                    projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), tmpDir.newFile()))),
            ImmutableMultimap.of(),
            ruleFinder);
    int exitCode =
        symlinkTree.getVerifyStep().execute(TestExecutionContext.newInstance()).getExitCode();
    assertThat(exitCode, Matchers.not(Matchers.equalTo(0)));
  }

  @Test
  public void resolveDuplicateRelativePathsIsNoopWhenThereAreNoDuplicates() {
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));

    ImmutableSortedSet<SourcePath> sourcePaths =
        ImmutableSortedSet.of(
            FakeSourcePath.of("one"), FakeSourcePath.of("two/two"), FakeSourcePath.of("three"));

    ImmutableBiMap<SourcePath, Path> resolvedDuplicates =
        SymlinkTree.resolveDuplicateRelativePaths(sourcePaths, resolver);

    assertThat(
        resolvedDuplicates.inverse(),
        Matchers.equalTo(FluentIterable.from(sourcePaths).uniqueIndex(resolver::getRelativePath)));
  }

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void resolveDuplicateRelativePaths() {
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));
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
            ImmutableSortedSet.copyOf(expected.keySet()), resolver);

    assertThat(resolvedDuplicates, Matchers.equalTo(expected));
  }

  @Test
  public void resolveDuplicateRelativePathsWithConflicts() {
    BuildRuleResolver ruleResolver = new TestActionGraphBuilder();
    SourcePathResolver resolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(ruleResolver));
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
            ImmutableSortedSet.copyOf(expected.keySet()), resolver);

    assertThat(resolvedDuplicates, Matchers.equalTo(expected));
  }

  @Test
  public void mergesDirectoryContentsIntoMainSymlinkTree() throws IOException {
    BuildTarget exportFileTarget1 = BuildTargetFactory.newInstance("//test:dir1");
    Path dir1 = Paths.get("test", "dir1");
    Path dir1file = dir1.resolve("file1");
    BuildTarget exportFileTarget2 = BuildTargetFactory.newInstance("//test:dir2");
    Path dir2 = Paths.get("test", "dir2");
    Path dir2file = dir1.resolve("file2");

    projectFilesystem.mkdirs(dir1);
    projectFilesystem.writeContentsToPath("file", dir1file);
    projectFilesystem.mkdirs(dir2);
    projectFilesystem.writeContentsToPath("file", dir2file);

    Genrule exportFile1 =
        GenruleBuilder.newGenruleBuilder(exportFileTarget1, projectFilesystem)
            .setOut("dir1")
            .setSrcs(ImmutableList.of(FakeSourcePath.of(projectFilesystem, dir1file)))
            .setCmd("mkdir -p $OUT && cp $SRCS $OUT")
            .setCmdExe("mkdir $OUT && copy $SRCS $OUT")
            .build(graphBuilder);
    Genrule exportFile2 =
        GenruleBuilder.newGenruleBuilder(exportFileTarget2, projectFilesystem)
            .setOut("dir2")
            .setSrcs(ImmutableList.of(FakeSourcePath.of(projectFilesystem, dir2file)))
            .setCmd("mkdir -p $OUT && cp $SRCS $OUT")
            .setCmdExe("mkdir $OUT && copy $SRCS $OUT")
            .build(graphBuilder);

    graphBuilder.computeIfAbsent(exportFileTarget1, target -> exportFile1);
    graphBuilder.computeIfAbsent(exportFileTarget2, target -> exportFile2);

    symlinkTreeBuildRule =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            links,
            ImmutableMultimap.of(
                Paths.get(""),
                exportFile1.getSourcePathToOutput(),
                Paths.get("subdir"),
                exportFile2.getSourcePathToOutput()),
            ruleFinder);

    BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    Stream.concat(
            Stream.concat(
                exportFile1.getBuildSteps(context, buildableContext).stream(),
                exportFile2.getBuildSteps(context, buildableContext).stream()),
            symlinkTreeBuildRule.getBuildSteps(context, buildableContext).stream())
        .forEach(
            step -> {
              try {
                step.execute(TestExecutionContext.newInstanceWithRealProcessExecutor());
              } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
              }
            });

    assertEquals(
        ImmutableSortedSet.of(exportFile1, exportFile2), symlinkTreeBuildRule.getBuildDeps());
    if (Platform.detect() != Platform.WINDOWS) {
      assertTrue(projectFilesystem.isSymLink(outputPath.resolve("file1")));
      assertTrue(projectFilesystem.isSymLink(outputPath.resolve("subdir").resolve("file2")));
    }
    assertTrue(
        Files.isSameFile(
            pathResolver.getAbsolutePath(exportFile1.getSourcePathToOutput()).resolve("file1"),
            projectFilesystem.resolve(outputPath.resolve("file1"))));
    assertTrue(
        Files.isSameFile(
            pathResolver.getAbsolutePath(exportFile2.getSourcePathToOutput()).resolve("file2"),
            projectFilesystem.resolve(outputPath.resolve("subdir").resolve("file2"))));
  }

  @Test
  public void failsOnMergeConflicts() throws IOException {
    exception.expectMessage("Tried to link");

    BuildTarget exportFileTarget1 = BuildTargetFactory.newInstance("//test:dir1");
    Path dir1 = Paths.get("test", "dir1");
    Path dir1file = dir1.resolve("file1");
    BuildTarget exportFileTarget2 = BuildTargetFactory.newInstance("//test:dir2");
    Path dir2 = Paths.get("test", "dir2");
    Path dir2file = dir1.resolve("file1");

    projectFilesystem.mkdirs(dir1);
    projectFilesystem.writeContentsToPath("file", dir1file);
    projectFilesystem.mkdirs(dir2);
    projectFilesystem.writeContentsToPath("file", dir2file);

    Genrule exportFile1 =
        GenruleBuilder.newGenruleBuilder(exportFileTarget1, projectFilesystem)
            .setOut("dir1")
            .setSrcs(ImmutableList.of(FakeSourcePath.of(projectFilesystem, dir1file)))
            .setCmd("mkdir -p $OUT && cp $SRCS $OUT")
            .setCmdExe("mkdir $OUT && copy $SRCS $OUT")
            .build(graphBuilder);
    Genrule exportFile2 =
        GenruleBuilder.newGenruleBuilder(exportFileTarget2, projectFilesystem)
            .setOut("dir2")
            .setSrcs(ImmutableList.of(FakeSourcePath.of(projectFilesystem, dir2file)))
            .setCmd("mkdir -p $OUT && cp $SRCS $OUT")
            .setCmdExe("mkdir $OUT && copy $SRCS $OUT")
            .build(graphBuilder);

    graphBuilder.computeIfAbsent(exportFileTarget1, target -> exportFile1);
    graphBuilder.computeIfAbsent(exportFileTarget2, target -> exportFile2);

    symlinkTreeBuildRule =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            links,
            ImmutableMultimap.of(
                Paths.get(""),
                exportFile1.getSourcePathToOutput(),
                Paths.get(""),
                exportFile2.getSourcePathToOutput()),
            ruleFinder);

    BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    Stream.concat(
            Stream.concat(
                exportFile1.getBuildSteps(context, buildableContext).stream(),
                exportFile2.getBuildSteps(context, buildableContext).stream()),
            symlinkTreeBuildRule.getBuildSteps(context, buildableContext).stream())
        .forEach(
            step -> {
              try {
                step.execute(TestExecutionContext.newInstanceWithRealProcessExecutor());
              } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
              }
            });
  }

  @Test
  public void failsOnMergeConflictsFromExplicitlyListedFiles() throws IOException {
    exception.expectMessage("Tried to link");

    BuildTarget exportFileTarget1 = BuildTargetFactory.newInstance("//test:dir1");
    Path dir1 = Paths.get("test", "dir1");
    Path dir1file = dir1.resolve("file");

    Genrule exportFile1 =
        GenruleBuilder.newGenruleBuilder(exportFileTarget1, projectFilesystem)
            .setOut("dir1")
            .setSrcs(ImmutableList.of(FakeSourcePath.of(projectFilesystem, dir1file)))
            .setCmd("mkdir $OUT && cp $SRCS $OUT")
            .setCmdExe("mkdir $OUT && copy $SRCS $OUT")
            .build(graphBuilder);

    projectFilesystem.mkdirs(dir1);
    projectFilesystem.writeContentsToPath("file", dir1file);
    graphBuilder.computeIfAbsent(exportFileTarget1, target -> exportFile1);

    symlinkTreeBuildRule =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            links,
            ImmutableMultimap.of(Paths.get(""), exportFile1.getSourcePathToOutput()),
            ruleFinder);

    BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    Stream.concat(
            exportFile1.getBuildSteps(context, buildableContext).stream(),
            symlinkTreeBuildRule.getBuildSteps(context, buildableContext).stream())
        .forEach(
            step -> {
              try {
                step.execute(TestExecutionContext.newInstanceWithRealProcessExecutor());
              } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
              }
            });
  }

  @Test
  public void getsCorrectCompileTimeDeps() {
    BuildTarget exportFileTarget = BuildTargetFactory.newInstance("//test:dir");
    Genrule exportFile =
        GenruleBuilder.newGenruleBuilder(exportFileTarget).setOut("dir1").build(graphBuilder);
    graphBuilder.computeIfAbsent(exportFileTarget, target -> exportFile);

    symlinkTreeBuildRule =
        new SymlinkTree(
            "link_tree",
            buildTarget,
            projectFilesystem,
            outputPath,
            links,
            ImmutableMultimap.of(Paths.get(""), exportFile.getSourcePathToOutput()),
            ruleFinder);

    assertEquals(ImmutableSortedSet.of(exportFile), symlinkTreeBuildRule.getBuildDeps());
  }
}
