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

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.event.BuckEventBusForTests.CapturingConsoleEventListener;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.hamcrest.text.MatchesPattern;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ExportFileTest {

  private ProjectFilesystem projectFilesystem;
  private BuildTarget target;

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Before
  public void createFixtures() {
    projectFilesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    target = BuildTargetFactory.newInstance(projectFilesystem.getRootPath(), "//:example.html");
  }

  @Test
  public void shouldSetSrcAndOutToNameParameterIfNeitherAreSet() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    ExportFile exportFile = new ExportFileBuilder(target).build(graphBuilder, projectFilesystem);

    List<Step> steps =
        exportFile.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver)
                .withBuildCellRootPath(projectFilesystem.getRootPath()),
            new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "rm -f -r " + Paths.get("buck-out/gen/example.html"),
            "mkdir -p " + Paths.get("buck-out/gen/example.html"),
            "cp "
                + projectFilesystem.resolve("example.html")
                + " "
                + Paths.get("buck-out/gen/example.html/example.html")),
        steps,
        TestExecutionContext.newInstance());
    assertEquals(
        BuildTargetPaths.getGenPath(projectFilesystem, target, "%s").resolve("example.html"),
        pathResolver.getRelativePath(exportFile.getSourcePathToOutput()));
  }

  @Test
  public void shouldSetOutToNameParamValueIfSrcIsSet() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    ExportFile exportFile =
        new ExportFileBuilder(target).setOut("fish").build(graphBuilder, projectFilesystem);

    List<Step> steps =
        exportFile.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver)
                .withBuildCellRootPath(projectFilesystem.getRootPath()),
            new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "rm -f -r " + Paths.get("buck-out/gen/example.html"),
            "mkdir -p " + Paths.get("buck-out/gen/example.html"),
            "cp "
                + projectFilesystem.resolve("example.html")
                + " "
                + Paths.get("buck-out/gen/example.html/fish")),
        steps,
        TestExecutionContext.newInstance());
    assertEquals(
        BuildTargetPaths.getGenPath(projectFilesystem, target, "%s").resolve("fish"),
        pathResolver.getRelativePath(exportFile.getSourcePathToOutput()));
  }

  @Test
  public void shouldSetOutAndSrcAndNameParametersSeparately() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    ExportFile exportFile =
        new ExportFileBuilder(target)
            .setSrc(PathSourcePath.of(projectFilesystem, Paths.get("chips")))
            .setOut("fish")
            .build(graphBuilder, projectFilesystem);

    List<Step> steps =
        exportFile.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver)
                .withBuildCellRootPath(projectFilesystem.getRootPath()),
            new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "rm -f -r " + Paths.get("buck-out/gen/example.html"),
            "mkdir -p " + Paths.get("buck-out/gen/example.html"),
            "cp "
                + projectFilesystem.resolve("chips")
                + " "
                + Paths.get("buck-out/gen/example.html/fish")),
        steps,
        TestExecutionContext.newInstance());
    assertEquals(
        BuildTargetPaths.getGenPath(projectFilesystem, target, "%s").resolve("fish"),
        pathResolver.getRelativePath(exportFile.getSourcePathToOutput()));
  }

  @Test
  public void shouldSetInputsFromSourcePaths() {
    ExportFileBuilder builder =
        new ExportFileBuilder(target).setSrc(FakeSourcePath.of("chips")).setOut("cake");

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    ExportFile exportFile = builder.build(graphBuilder, projectFilesystem);

    assertIterablesEquals(
        singleton(Paths.get("chips")),
        pathResolver.filterInputsToCompareToOutput(exportFile.getSource()));

    graphBuilder = new TestActionGraphBuilder();
    pathResolver = DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    FakeBuildRule rule =
        graphBuilder.addToIndex(new FakeBuildRule(BuildTargetFactory.newInstance("//example:one")));

    builder.setSrc(DefaultBuildTargetSourcePath.of(rule.getBuildTarget()));
    exportFile = builder.build(graphBuilder, projectFilesystem);
    assertThat(
        pathResolver.filterInputsToCompareToOutput(exportFile.getSource()), Matchers.empty());

    graphBuilder = new TestActionGraphBuilder();
    pathResolver = DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    builder.setSrc(null);
    exportFile = builder.build(graphBuilder, projectFilesystem);
    assertIterablesEquals(
        singleton(projectFilesystem.getPath("example.html")),
        pathResolver.filterInputsToCompareToOutput(exportFile.getSource()));
  }

  @Test
  public void getOutputName() {
    ExportFile exportFile =
        new ExportFileBuilder(target)
            .setOut("cake")
            .build(new TestActionGraphBuilder(), projectFilesystem);

    assertEquals("cake", exportFile.getOutputName());
  }

  @Test
  public void modifyingTheContentsOfTheFileChangesTheRuleKey() throws Exception {
    Path root = Files.createTempDirectory("root");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(root);
    Path temp = Paths.get("example_file");

    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    filesystem, FileHashCacheMode.DEFAULT)));
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver resolver = DefaultSourcePathResolver.from(ruleFinder);
    DefaultRuleKeyFactory ruleKeyFactory =
        new TestDefaultRuleKeyFactory(hashCache, resolver, ruleFinder);

    filesystem.writeContentsToPath("I like cheese", temp);

    ExportFileBuilder builder =
        new ExportFileBuilder(BuildTargetFactory.newInstance("//some:file"))
            .setSrc(PathSourcePath.of(filesystem, temp));

    ExportFile rule = builder.build(new TestActionGraphBuilder(), filesystem);

    RuleKey original = ruleKeyFactory.build(rule);

    filesystem.writeContentsToPath("I really like cheese", temp);

    // Create a new rule. The FileHashCache held by the existing rule will retain a reference to the
    // previous content of the file, so we need to create an identical rule.
    rule = builder.build(new TestActionGraphBuilder(), filesystem);

    hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    filesystem, FileHashCacheMode.DEFAULT)));
    ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    resolver = DefaultSourcePathResolver.from(ruleFinder);
    ruleKeyFactory = new TestDefaultRuleKeyFactory(hashCache, resolver, ruleFinder);
    RuleKey refreshed = ruleKeyFactory.build(rule);

    assertNotEquals(original, refreshed);
  }

  @Test
  public void referenceModeUsesUnderlyingSourcePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    SourcePath src = FakeSourcePath.of(projectFilesystem, "source");
    ExportFile exportFile =
        new ExportFileBuilder(target)
            .setMode(ExportFileDescription.Mode.REFERENCE)
            .setSrc(src)
            .build(graphBuilder, projectFilesystem);
    assertThat(
        pathResolver.getRelativePath(exportFile.getSourcePathToOutput()),
        Matchers.equalTo(pathResolver.getRelativePath(src)));
  }

  @Test
  public void referenceModeRequiresSameFilesystem() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    ProjectFilesystem differentFilesystem = new FakeProjectFilesystem();
    SourcePath src = FakeSourcePath.of(differentFilesystem, "source");
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("must use `COPY` mode"));
    new ExportFileBuilder(target)
        .setMode(ExportFileDescription.Mode.REFERENCE)
        .setSrc(src)
        .build(graphBuilder, projectFilesystem);
  }

  @Test
  public void referenceModeDoesNotAcceptOutParameter() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(Matchers.containsString("must not set `out`"));
    new ExportFileBuilder(target)
        .setOut("out")
        .setMode(ExportFileDescription.Mode.REFERENCE)
        .build(graphBuilder, projectFilesystem);
  }

  @Test
  public void referenceModeExposesUnderlyingBuildTargetAsRuntimeDep() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);

    Genrule genrule =
        GenruleBuilder.newGenruleBuilder(
                BuildTargetFactory.newInstance(projectFilesystem.getRootPath(), "//:genrule"),
                projectFilesystem)
            .setOut("out")
            .setCmd("")
            .build(graphBuilder, projectFilesystem);
    ExportFile exportFile =
        new ExportFileBuilder(target)
            .setMode(ExportFileDescription.Mode.REFERENCE)
            .setSrc(genrule.getSourcePathToOutput())
            .build(graphBuilder, projectFilesystem);

    assertEquals(
        ImmutableList.of(genrule.getBuildTarget()),
        exportFile.getRuntimeDeps(ruleFinder).collect(Collectors.toList()));
  }

  @Test
  public void testDirectoryActionFailOnDirectory() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage(
        MatchesPattern.matchesPattern("Trying to export a directory .* but it is not allowed."));

    Path sourceDir = Paths.get("dir");
    projectFilesystem.mkdirs(sourceDir);
    ExportFile exportFile =
        new ExportFileBuilder(
                FakeBuckConfig.builder()
                    .setSections("[export_file]", "input_directory_action = fail")
                    .build(),
                target)
            .setOut("out")
            .setSrc(FakeSourcePath.of(projectFilesystem, sourceDir))
            .build(graphBuilder, projectFilesystem);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    exportFile.getBuildSteps(
        FakeBuildContext.withSourcePathResolver(pathResolver)
            .withBuildCellRootPath(projectFilesystem.getRootPath()),
        new FakeBuildableContext());
  }

  @Test
  public void testDirectoryActionWarnOnDirectory() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    Path sourceDir = Paths.get("dir");
    projectFilesystem.mkdirs(sourceDir);
    ExportFile exportFile =
        new ExportFileBuilder(
                FakeBuckConfig.builder()
                    .setSections("[export_file]", "input_directory_action = warn")
                    .build(),
                target)
            .setOut("out")
            .setSrc(FakeSourcePath.of(projectFilesystem, sourceDir))
            .build(graphBuilder, projectFilesystem);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    CapturingConsoleEventListener listener = new CapturingConsoleEventListener();
    buckEventBus.register(listener);

    exportFile.getBuildSteps(
        FakeBuildContext.create(pathResolver, buckEventBus)
            .withBuildCellRootPath(projectFilesystem.getRootPath()),
        new FakeBuildableContext());

    assertThat(
        listener.getLogMessages().get(0),
        MatchesPattern.matchesPattern("Trying to export a directory .* but it is not allowed."));
  }

  @Test
  public void testDirectoryActionSkipOnDirectory() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    Path sourceDir = Paths.get("dir");
    projectFilesystem.mkdirs(sourceDir);
    ExportFile exportFile =
        new ExportFileBuilder(FakeBuckConfig.builder().build(), target)
            .setOut("out")
            .setSrc(FakeSourcePath.of(projectFilesystem, sourceDir))
            .build(graphBuilder, projectFilesystem);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    BuckEventBus buckEventBus = BuckEventBusForTests.newInstance();
    CapturingConsoleEventListener listener = new CapturingConsoleEventListener();
    buckEventBus.register(listener);

    exportFile.getBuildSteps(
        FakeBuildContext.create(pathResolver, buckEventBus)
            .withBuildCellRootPath(projectFilesystem.getRootPath()),
        new FakeBuildableContext());
  }
}
