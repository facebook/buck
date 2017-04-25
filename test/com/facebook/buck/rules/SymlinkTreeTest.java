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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyFactory;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
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
  private SourcePathRuleFinder ruleFinder;
  private SourcePathResolver pathResolver;
  private BuildRuleResolver ruleResolver;

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
    outputPath = BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s/symlink-tree-root");

    ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ruleFinder = new SourcePathRuleFinder(ruleResolver);
    pathResolver = new SourcePathResolver(ruleFinder);

    // Setup the symlink tree buildable.
    symlinkTreeBuildRule =
        new SymlinkTree(buildTarget, projectFilesystem, outputPath, links, ruleFinder);
  }

  @Test
  public void testSymlinkTreeBuildSteps() throws IOException {

    // Create the fake build contexts.
    BuildContext buildContext = FakeBuildContext.withSourcePathResolver(pathResolver);
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    // Verify the build steps are as expected.
    ImmutableList<Step> expectedBuildSteps =
        new ImmutableList.Builder<Step>()
            .addAll(MakeCleanDirectoryStep.of(projectFilesystem, outputPath))
            .add(
                new SymlinkTreeStep(
                    projectFilesystem, outputPath, pathResolver.getMappedPaths(links)))
            .build();
    ImmutableList<Step> actualBuildSteps =
        symlinkTreeBuildRule.getBuildSteps(buildContext, buildableContext);
    assertEquals(expectedBuildSteps, actualBuildSteps.subList(1, actualBuildSteps.size()));
  }

  @Test
  public void testSymlinkTreeRuleKeyChangesIfLinkMapChanges() throws Exception {
    // Create a BuildRule wrapping the stock SymlinkTree buildable.
    //BuildRule rule1 = symlinkTreeBuildable;

    // Also create a new BuildRule based around a SymlinkTree buildable with a different
    // link map.
    Path aFile = tmpDir.newFile();
    Files.write(aFile, "hello world".getBytes(Charsets.UTF_8));
    SymlinkTree modifiedSymlinkTreeBuildRule =
        new SymlinkTree(
            buildTarget,
            projectFilesystem,
            outputPath,
            ImmutableMap.of(
                Paths.get("different/link"),
                new PathSourcePath(
                    projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), aFile))),
            ruleFinder);
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);

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
  public void testSymlinkTreeRuleKeyDoesNotChangeIfLinkTargetsChangeOnUnix() throws IOException {
    ruleResolver.addToIndex(symlinkTreeBuildRule);

    InputBasedRuleKeyFactory ruleKeyFactory =
        new InputBasedRuleKeyFactory(
            0, FakeFileHashCache.createFromStrings(ImmutableMap.of()), pathResolver, ruleFinder);

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
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ruleResolver.addToIndex(symlinkTreeBuildRule);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setSrcs(ImmutableList.of(symlinkTreeBuildRule.getSourcePathToOutput()))
            .setOut("out")
            .build(ruleResolver);

    DefaultFileHashCache hashCache =
        DefaultFileHashCache.createDefaultFileHashCache(new ProjectFilesystem(tmpDir.getRoot()));
    FileHashLoader hashLoader = new StackedFileHashCache(ImmutableList.of(hashCache));
    RuleKey ruleKey1 =
        new DefaultRuleKeyFactory(0, hashLoader, pathResolver, ruleFinder).build(genrule);

    Path existingFile = pathResolver.getAbsolutePath(links.values().asList().get(0));
    Files.write(existingFile, "something new".getBytes(Charsets.UTF_8));
    hashCache.invalidateAll();

    RuleKey ruleKey2 =
        new DefaultRuleKeyFactory(0, hashLoader, pathResolver, ruleFinder).build(genrule);

    // Verify that the rules keys are different.
    assertNotEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void testSymlinkTreeInputBasedRuleKeysAreImmuneToLinkSourceContentChanges()
      throws Exception {
    Genrule dep =
        GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(ruleResolver);

    symlinkTreeBuildRule =
        new SymlinkTree(
            buildTarget,
            projectFilesystem,
            outputPath,
            ImmutableMap.of(Paths.get("link"), dep.getSourcePathToOutput()),
            ruleFinder);

    // Generate an input-based rule key for the symlink tree with the contents of the link
    // target hashing to "aaaa".
    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(ImmutableMap.of("out", "aaaa"));
    InputBasedRuleKeyFactory inputBasedRuleKeyFactory =
        new InputBasedRuleKeyFactory(0, hashCache, pathResolver, ruleFinder);
    RuleKey ruleKey1 = inputBasedRuleKeyFactory.build(symlinkTreeBuildRule);

    // Generate an input-based rule key for the symlink tree with the contents of the link
    // target hashing to a different value: "bbbb".
    hashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of("out", "bbbb"));
    inputBasedRuleKeyFactory = new InputBasedRuleKeyFactory(0, hashCache, pathResolver, ruleFinder);
    RuleKey ruleKey2 = inputBasedRuleKeyFactory.build(symlinkTreeBuildRule);

    // Verify that the rules keys are the same.
    assertEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void verifyStepFailsIfKeyContainsDotDot() throws Exception {
    SymlinkTree symlinkTree =
        new SymlinkTree(
            buildTarget,
            projectFilesystem,
            outputPath,
            ImmutableMap.of(
                Paths.get("../something"),
                new PathSourcePath(
                    projectFilesystem, MorePaths.relativize(tmpDir.getRoot(), tmpDir.newFile()))),
            ruleFinder);
    int exitCode =
        symlinkTree.getVerifyStep().execute(TestExecutionContext.newInstance()).getExitCode();
    assertThat(exitCode, Matchers.not(Matchers.equalTo(0)));
  }

  @Test
  public void resolveDuplicateRelativePathsIsNoopWhenThereAreNoDuplicates() {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver resolver = new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));

    ImmutableSortedSet<SourcePath> sourcePaths =
        ImmutableSortedSet.of(
            new FakeSourcePath("one"), new FakeSourcePath("two/two"), new FakeSourcePath("three"));

    ImmutableBiMap<SourcePath, Path> resolvedDuplicates =
        SymlinkTree.resolveDuplicateRelativePaths(sourcePaths, resolver);

    assertThat(
        resolvedDuplicates.inverse(),
        Matchers.equalTo(FluentIterable.from(sourcePaths).uniqueIndex(resolver::getRelativePath)));
  }

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void resolveDuplicateRelativePaths() throws InterruptedException, IOException {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver resolver = new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));
    tmp.getRoot().resolve("one").toFile().mkdir();
    tmp.getRoot().resolve("two").toFile().mkdir();
    ProjectFilesystem fsOne = new ProjectFilesystem(tmp.getRoot().resolve("one"));
    ProjectFilesystem fsTwo = new ProjectFilesystem(tmp.getRoot().resolve("two"));

    ImmutableBiMap<SourcePath, Path> expected =
        ImmutableBiMap.of(
            new FakeSourcePath(fsOne, "a/one.a"), Paths.get("a/one.a"),
            new FakeSourcePath(fsOne, "a/two"), Paths.get("a/two"),
            new FakeSourcePath(fsTwo, "a/one.a"), Paths.get("a/one-1.a"),
            new FakeSourcePath(fsTwo, "a/two"), Paths.get("a/two-1"));

    ImmutableBiMap<SourcePath, Path> resolvedDuplicates =
        SymlinkTree.resolveDuplicateRelativePaths(
            ImmutableSortedSet.copyOf(expected.keySet()), resolver);

    assertThat(resolvedDuplicates, Matchers.equalTo(expected));
  }

  @Test
  public void resolveDuplicateRelativePathsWithConflicts() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver resolver = new SourcePathResolver(new SourcePathRuleFinder(ruleResolver));
    tmp.getRoot().resolve("a-fs").toFile().mkdir();
    tmp.getRoot().resolve("b-fs").toFile().mkdir();
    tmp.getRoot().resolve("c-fs").toFile().mkdir();
    ProjectFilesystem fsOne = new ProjectFilesystem(tmp.getRoot().resolve("a-fs"));
    ProjectFilesystem fsTwo = new ProjectFilesystem(tmp.getRoot().resolve("b-fs"));
    ProjectFilesystem fsThree = new ProjectFilesystem(tmp.getRoot().resolve("c-fs"));

    ImmutableBiMap<SourcePath, Path> expected =
        ImmutableBiMap.<SourcePath, Path>builder()
            .put(new FakeSourcePath(fsOne, "a/one.a"), Paths.get("a/one.a"))
            .put(new FakeSourcePath(fsOne, "a/two"), Paths.get("a/two"))
            .put(new FakeSourcePath(fsOne, "a/two-1"), Paths.get("a/two-1"))
            .put(new FakeSourcePath(fsTwo, "a/one.a"), Paths.get("a/one-1.a"))
            .put(new FakeSourcePath(fsTwo, "a/two"), Paths.get("a/two-2"))
            .put(new FakeSourcePath(fsThree, "a/two"), Paths.get("a/two-3"))
            .build();

    ImmutableBiMap<SourcePath, Path> resolvedDuplicates =
        SymlinkTree.resolveDuplicateRelativePaths(
            ImmutableSortedSet.copyOf(expected.keySet()), resolver);

    assertThat(resolvedDuplicates, Matchers.equalTo(expected));
  }
}
