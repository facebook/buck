/*
 * Copyright 2012-present Facebook, Inc.
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
package com.facebook.buck.shell;

import static com.facebook.buck.testutil.MoreAsserts.assertIterablesEquals;
import static java.util.Collections.singleton;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ExportFileTest {

  private ProjectFilesystem projectFilesystem;
  private BuildTarget target;

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Before
  public void createFixtures() throws InterruptedException {
    projectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    target = BuildTargetFactory.newInstance(projectFilesystem.getRootPath(), "//:example.html");
  }

  @Test
  public void shouldSetSrcAndOutToNameParameterIfNeitherAreSet() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    ExportFile exportFile =
        ExportFileBuilder.newExportFileBuilder(target).build(resolver, projectFilesystem);

    List<Step> steps =
        exportFile.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "mkdir -p " + projectFilesystem.resolve("buck-out/gen"),
            "rm -f -r " + projectFilesystem.resolve("buck-out/gen/example.html"),
            "cp "
                + projectFilesystem.resolve("example.html")
                + " "
                + Paths.get("buck-out/gen/example.html")),
        steps,
        TestExecutionContext.newInstance());
    assertEquals(
        projectFilesystem.getBuckPaths().getGenDir().resolve("example.html"),
        pathResolver.getRelativePath(exportFile.getSourcePathToOutput()));
  }

  @Test
  public void shouldSetOutToNameParamValueIfSrcIsSet() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    ExportFile exportFile =
        ExportFileBuilder.newExportFileBuilder(target)
            .setOut("fish")
            .build(resolver, projectFilesystem);

    List<Step> steps =
        exportFile.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "mkdir -p " + projectFilesystem.resolve("buck-out/gen"),
            "rm -f -r " + projectFilesystem.resolve("buck-out/gen/fish"),
            "cp "
                + projectFilesystem.resolve("example.html")
                + " "
                + Paths.get("buck-out/gen/fish")),
        steps,
        TestExecutionContext.newInstance());
    assertEquals(
        projectFilesystem.getBuckPaths().getGenDir().resolve("fish"),
        pathResolver.getRelativePath(exportFile.getSourcePathToOutput()));
  }

  @Test
  public void shouldSetOutAndSrcAndNameParametersSeparately() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    ExportFile exportFile =
        ExportFileBuilder.newExportFileBuilder(target)
            .setSrc(new PathSourcePath(projectFilesystem, Paths.get("chips")))
            .setOut("fish")
            .build(resolver, projectFilesystem);

    List<Step> steps =
        exportFile.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "mkdir -p " + projectFilesystem.resolve("buck-out/gen"),
            "rm -f -r " + projectFilesystem.resolve("buck-out/gen/fish"),
            "cp " + projectFilesystem.resolve("chips") + " " + Paths.get("buck-out/gen/fish")),
        steps,
        TestExecutionContext.newInstance());
    assertEquals(
        projectFilesystem.getBuckPaths().getGenDir().resolve("fish"),
        pathResolver.getRelativePath(exportFile.getSourcePathToOutput()));
  }

  @Test
  public void shouldSetInputsFromSourcePaths() throws Exception {
    ExportFileBuilder builder =
        ExportFileBuilder.newExportFileBuilder(target)
            .setSrc(new FakeSourcePath("chips"))
            .setOut("cake");

    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    ExportFile exportFile = builder.build(resolver, projectFilesystem);

    assertIterablesEquals(
        singleton(Paths.get("chips")),
        pathResolver.filterInputsToCompareToOutput(exportFile.getSource()));

    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    FakeBuildRule rule =
        resolver.addToIndex(
            new FakeBuildRule(BuildTargetFactory.newInstance("//example:one"), pathResolver));

    builder.setSrc(new DefaultBuildTargetSourcePath(rule.getBuildTarget()));
    exportFile = builder.build(resolver, projectFilesystem);
    assertThat(
        pathResolver.filterInputsToCompareToOutput(exportFile.getSource()), Matchers.empty());

    resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));

    builder.setSrc(null);
    exportFile = builder.build(resolver, projectFilesystem);
    assertIterablesEquals(
        singleton(projectFilesystem.getPath("example.html")),
        pathResolver.filterInputsToCompareToOutput(exportFile.getSource()));
  }

  @Test
  public void getOutputName() throws Exception {
    ExportFile exportFile =
        ExportFileBuilder.newExportFileBuilder(target)
            .setOut("cake")
            .build(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()),
                projectFilesystem);

    assertEquals("cake", exportFile.getOutputName());
  }

  @Test
  public void modifyingTheContentsOfTheFileChangesTheRuleKey() throws Exception {
    Path root = Files.createTempDirectory("root");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(root);
    Path temp = Paths.get("example_file");

    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(0, hashCache, resolver, ruleFinder);

    filesystem.writeContentsToPath("I like cheese", temp);

    ExportFileBuilder builder =
        ExportFileBuilder.newExportFileBuilder(BuildTargetFactory.newInstance("//some:file"))
            .setSrc(new PathSourcePath(filesystem, temp));

    ExportFile rule =
        builder.build(
            new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()),
            filesystem);

    RuleKey original = ruleKeyFactory.build(rule);

    filesystem.writeContentsToPath("I really like cheese", temp);

    // Create a new rule. The FileHashCache held by the existing rule will retain a reference to the
    // previous content of the file, so we need to create an identical rule.
    rule =
        builder.build(
            new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()),
            filesystem);

    hashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    resolver = new SourcePathResolver(ruleFinder);
    ruleKeyFactory = new DefaultRuleKeyFactory(0, hashCache, resolver, ruleFinder);
    RuleKey refreshed = ruleKeyFactory.build(rule);

    assertNotEquals(original, refreshed);
  }

  @Test
  public void referenceModeUsesUnderlyingSourcePath() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    SourcePath src = new FakeSourcePath(projectFilesystem, "source");
    ExportFile exportFile =
        ExportFileBuilder.newExportFileBuilder(target)
            .setMode(ExportFileDescription.Mode.REFERENCE)
            .setSrc(src)
            .build(resolver, projectFilesystem);
    assertThat(
        pathResolver.getRelativePath(exportFile.getSourcePathToOutput()),
        Matchers.equalTo(pathResolver.getRelativePath(src)));
  }

  @Test
  public void referenceModeRequiresSameFilesystem() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    ProjectFilesystem differentFilesystem = new FakeProjectFilesystem();
    SourcePath src = new FakeSourcePath(differentFilesystem, "source");
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("must use `COPY` mode"));
    ExportFileBuilder.newExportFileBuilder(target)
        .setMode(ExportFileDescription.Mode.REFERENCE)
        .setSrc(src)
        .build(resolver, projectFilesystem);
  }

  @Test
  public void referenceModeDoesNotAcceptOutParameter() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("must not set `out`"));
    ExportFileBuilder.newExportFileBuilder(target)
        .setOut("out")
        .setMode(ExportFileDescription.Mode.REFERENCE)
        .build(resolver, projectFilesystem);
  }
}
