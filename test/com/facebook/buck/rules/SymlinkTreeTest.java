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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.rules.keys.InputBasedRuleKeyBuilderFactory;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.SymlinkTreeStep;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.base.Charsets;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.hamcrest.Matchers;
import org.hamcrest.junit.ExpectedException;
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

  @Rule
  public final ExpectedException exception = ExpectedException.none();

  private ProjectFilesystem projectFilesystem;
  private BuildTarget buildTarget;
  private SymlinkTree symlinkTreeBuildRule;
  private ImmutableMap<Path, SourcePath> links;
  private Path outputPath;

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
    outputPath = projectFilesystem.resolve(
        BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s/symlink-tree-root"));

    // Setup the symlink tree buildable.
    symlinkTreeBuildRule = new SymlinkTree(
        new FakeBuildRuleParamsBuilder(buildTarget).build(),
        new SourcePathResolver(
            new BuildRuleResolver(TargetGraph.EMPTY,
                new DefaultTargetNodeToBuildRuleTransformer())),
        outputPath,
        links);

  }

  @Test
  public void testSymlinkTreeBuildStepsAreEmpty() throws IOException {

    // Create the fake build contexts.
    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    // Verify the build steps are as expected.
    ImmutableList<Step> actualBuildSteps =
        symlinkTreeBuildRule.getBuildSteps(
            buildContext,
            buildableContext);
    assertThat(actualBuildSteps, Matchers.empty());
  }

  @Test
  public void testSymlinkTreePostBuildSteps() throws IOException {

    // Create the fake build contexts.
    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

    // Verify the build steps are as expected.
    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    ImmutableList<Step> expectedBuildSteps =
        ImmutableList.of(
            new MakeCleanDirectoryStep(filesystem, outputPath),
            new SymlinkTreeStep(
                filesystem,
                outputPath,
                resolver.getMappedPaths(links)));
    ImmutableList<Step> actualBuildSteps =
        symlinkTreeBuildRule.getPostBuildSteps(
            buildContext,
            buildableContext);
    assertEquals(expectedBuildSteps, actualBuildSteps.subList(1, actualBuildSteps.size()));
  }

  @Test
  public void testSymlinkTreeRuleKeyChangesIfLinkMapChanges() throws Exception {

    // Create a BuildRule wrapping the stock SymlinkTree buildable.
    //BuildRule rule1 = symlinkTreeBuildable;

    // Also create a new BuildRule based around a SymlinkTree buildable with a different
    // link map.
    Path aFile = tmpDir.newFile().toPath();
    Files.write(aFile, "hello world".getBytes(Charsets.UTF_8));
    AbstractBuildRule modifiedSymlinkTreeBuildRule = new SymlinkTree(
        new FakeBuildRuleParamsBuilder(buildTarget).build(),
        new SourcePathResolver(
            new BuildRuleResolver(
                TargetGraph.EMPTY,
                new DefaultTargetNodeToBuildRuleTransformer())),
        outputPath,
        ImmutableMap.<Path, SourcePath>of(
            Paths.get("different/link"),
            new PathSourcePath(
                projectFilesystem,
                MorePaths.relativize(tmpDir.getRoot().toPath(), aFile))));
    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );

    // Calculate their rule keys and verify they're different.
    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(
        ImmutableMap.<String, String>of());
    RuleKey key1 = new DefaultRuleKeyBuilderFactory(0, hashCache, resolver).build(
        symlinkTreeBuildRule);
    RuleKey key2 = new DefaultRuleKeyBuilderFactory(0, hashCache, resolver).build(
        modifiedSymlinkTreeBuildRule);
    assertNotEquals(key1, key2);
  }

  @Test
  public void testSymlinkTreeRuleKeyDoesNotChangeIfLinkTargetsChangeOnUnix() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver(
        TargetGraph.EMPTY,
        new DefaultTargetNodeToBuildRuleTransformer());
    ruleResolver.addToIndex(symlinkTreeBuildRule);
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    DefaultRuleKeyBuilderFactory ruleKeyBuilderFactory = new DefaultRuleKeyBuilderFactory(
        0,
        FakeFileHashCache.createFromStrings(
            ImmutableMap.<String, String>of()),
        resolver);

    // Calculate the rule key
    RuleKey key1 = ruleKeyBuilderFactory.build(symlinkTreeBuildRule);

    // Change the contents of the target of the link.
    Path existingFile =
        projectFilesystem.resolve(resolver.deprecatedGetPath(links.values().asList().get(0)));
    Files.write(existingFile, "something new".getBytes(Charsets.UTF_8));

    // Re-calculate the rule key
    RuleKey key2 = ruleKeyBuilderFactory.build(symlinkTreeBuildRule);

    // Verify that the rules keys are the same.
    assertEquals(key1, key2);

  }

  @Test
  public void testSymlinkTreeInputBasedRuleKeysAreImmuneToDependencyChanges() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(
        ImmutableMap.<String, String>of());
    InputBasedRuleKeyBuilderFactory inputBasedRuleKeyBuilderFactory =
        new InputBasedRuleKeyBuilderFactory(
            0,
            hashCache,
            pathResolver);

    FakeBuildRule dep = new FakeBuildRule("//:dep", pathResolver);
    symlinkTreeBuildRule =
        new SymlinkTree(
            new FakeBuildRuleParamsBuilder(buildTarget)
                .setDeclaredDeps(ImmutableSortedSet.<BuildRule>of(dep))
                .build(),
            new SourcePathResolver(
                new BuildRuleResolver(
                    TargetGraph.EMPTY,
                    new DefaultTargetNodeToBuildRuleTransformer())),
            outputPath,
            links);

    // Generate an input-based rule key for the symlink tree.
    RuleKey ruleKey1 =
        inputBasedRuleKeyBuilderFactory.build(symlinkTreeBuildRule);

    // Change the dep's rule key and re-calculate the input-based rule key.
    RuleKey ruleKey2 =
        inputBasedRuleKeyBuilderFactory.build(symlinkTreeBuildRule);

    // Verify that the rules keys are the same.
    assertEquals(ruleKey1, ruleKey2);
  }


  @Test
  public void testSymlinkTreeInputBasedRuleKeysAreImmuneToLinkSourceContentChanges()
      throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    Genrule dep =
        (Genrule) GenruleBuilder.newGenruleBuilder(BuildTargetFactory.newInstance("//:dep"))
            .setOut("out")
            .build(resolver);

    symlinkTreeBuildRule =
        new SymlinkTree(
            new FakeBuildRuleParamsBuilder(buildTarget)
                .setDeclaredDeps(ImmutableSortedSet.<BuildRule>of(dep))
                .build(),
            pathResolver,
            outputPath,
            ImmutableMap.<Path, SourcePath>of(
                Paths.get("link"),
                new BuildTargetSourcePath(dep.getBuildTarget())));

    // Generate an input-based rule key for the symlink tree with the contents of the link
    // target hashing to "aaaa".
    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(
        ImmutableMap.of("out", "aaaa"));
    InputBasedRuleKeyBuilderFactory inputBasedRuleKeyBuilderFactory =
        new InputBasedRuleKeyBuilderFactory(
            0,
            hashCache,
            pathResolver);
    RuleKey ruleKey1 =
        inputBasedRuleKeyBuilderFactory.build(symlinkTreeBuildRule);

    // Generate an input-based rule key for the symlink tree with the contents of the link
    // target hashing to a different value: "bbbb".
    hashCache = FakeFileHashCache.createFromStrings(
        ImmutableMap.of("out", "bbbb"));
    inputBasedRuleKeyBuilderFactory =
        new InputBasedRuleKeyBuilderFactory(
            0,
            hashCache,
            pathResolver);
    RuleKey ruleKey2 =
        inputBasedRuleKeyBuilderFactory.build(symlinkTreeBuildRule);

    // Verify that the rules keys are the same.
    assertEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void verifyStepFailsIfKeyContainsDotDot() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    SymlinkTree symlinkTree =
        new SymlinkTree(
            new FakeBuildRuleParamsBuilder(buildTarget).build(),
            pathResolver,
            outputPath,
            ImmutableMap.<Path, SourcePath>of(
                Paths.get("../something"),
                new PathSourcePath(
                    projectFilesystem,
                    MorePaths.relativize(tmpDir.getRoot().toPath(), tmpDir.newFile().toPath()))));
    int exitCode = symlinkTree.getVerifiyStep().execute(TestExecutionContext.newInstance())
                   .getExitCode();
    assertThat(exitCode, Matchers.not(Matchers.equalTo(0)));
  }

  @Test
  public void resolveDuplicateRelativePathsIsNoopWhenThereAreNoDuplicates() {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);

    ImmutableSortedSet<SourcePath> sourcePaths = ImmutableSortedSet.<SourcePath>of(
        new FakeSourcePath("one"),
        new FakeSourcePath("two/two"),
        new FakeSourcePath("three")
    );

    ImmutableBiMap<SourcePath, Path> resolvedDuplicates = SymlinkTree.resolveDuplicateRelativePaths(
        sourcePaths,
        resolver);

    assertThat(
        resolvedDuplicates.inverse(),
        Matchers.equalTo(
            FluentIterable.from(sourcePaths)
            .uniqueIndex(resolver.getRelativePathFunction())));
  }

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void resolveDuplicateRelativePaths() throws IOException {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);
    tmp.getRootPath().resolve("one").toFile().mkdir();
    tmp.getRootPath().resolve("two").toFile().mkdir();
    ProjectFilesystem fsOne = new ProjectFilesystem(tmp.getRootPath().resolve("one"));
    ProjectFilesystem fsTwo = new ProjectFilesystem(tmp.getRootPath().resolve("two"));

    ImmutableBiMap<SourcePath, Path> expected = ImmutableBiMap.<SourcePath, Path>of(
        new FakeSourcePath(fsOne, "a/one.a"), Paths.get("a/one.a"),
        new FakeSourcePath(fsOne, "a/two"), Paths.get("a/two"),
        new FakeSourcePath(fsTwo, "a/one.a"), Paths.get("a/one-1.a"),
        new FakeSourcePath(fsTwo, "a/two"),  Paths.get("a/two-1")
    );

    ImmutableBiMap<SourcePath, Path> resolvedDuplicates = SymlinkTree.resolveDuplicateRelativePaths(
        ImmutableSortedSet.copyOf(expected.keySet()),
        resolver);

    assertThat(resolvedDuplicates, Matchers.equalTo(expected));
  }

  @Test
  public void resolveDuplicateRelativePathsWithConflicts() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver resolver = new SourcePathResolver(ruleResolver);
    tmp.getRootPath().resolve("a-fs").toFile().mkdir();
    tmp.getRootPath().resolve("b-fs").toFile().mkdir();
    tmp.getRootPath().resolve("c-fs").toFile().mkdir();
    ProjectFilesystem fsOne = new ProjectFilesystem(tmp.getRootPath().resolve("a-fs"));
    ProjectFilesystem fsTwo = new ProjectFilesystem(tmp.getRootPath().resolve("b-fs"));
    ProjectFilesystem fsThree = new ProjectFilesystem(tmp.getRootPath().resolve("c-fs"));

    ImmutableBiMap<SourcePath, Path> expected = ImmutableBiMap.<SourcePath, Path>builder()
        .put(new FakeSourcePath(fsOne, "a/one.a"), Paths.get("a/one.a"))
        .put(new FakeSourcePath(fsOne, "a/two"), Paths.get("a/two"))
        .put(new FakeSourcePath(fsOne, "a/two-1"), Paths.get("a/two-1"))
        .put(new FakeSourcePath(fsTwo, "a/one.a"), Paths.get("a/one-1.a"))
        .put(new FakeSourcePath(fsTwo, "a/two"), Paths.get("a/two-2"))
        .put(new FakeSourcePath(fsThree, "a/two"), Paths.get("a/two-3"))
        .build();

    ImmutableBiMap<SourcePath, Path> resolvedDuplicates = SymlinkTree.resolveDuplicateRelativePaths(
        ImmutableSortedSet.copyOf(expected.keySet()),
        resolver);

    assertThat(resolvedDuplicates, Matchers.equalTo(expected));
  }

}
