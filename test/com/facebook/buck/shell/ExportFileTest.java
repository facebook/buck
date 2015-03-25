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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singleton;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.ImmutableBuildContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.DefaultFileHashCache;
import com.facebook.buck.util.FileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

public class ExportFileTest {

  private BuildContext context;
  private BuildTarget target;

  @Before
  public void createFixtures() {
    target = BuildTarget.builder("//", "example.html").build();
    File root = new File(".");
    context = getBuildContext(root);
  }

  @Test
  public void shouldSetSrcAndOutToNameParameterIfNeitherAreSet() throws IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ExportFile exportFile = (ExportFile) ExportFileBuilder.newExportFileBuilder(target)
        .build(new BuildRuleResolver(), projectFilesystem);

    List<Step> steps = exportFile.getBuildSteps(context, new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "mkdir -p buck-out/gen",
            "cp " + projectFilesystem.resolve("example.html") + " buck-out/gen/example.html"),
        steps,
        TestExecutionContext.newInstance());
    assertEquals(Paths.get("buck-out/gen/example.html"), exportFile.getPathToOutputFile());
  }

  @Test
  public void shouldSetOutToNameParamValueIfSrcIsSet() throws IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ExportFile exportFile = (ExportFile) ExportFileBuilder.newExportFileBuilder(target)
        .setOut("fish")
        .build(new BuildRuleResolver(), projectFilesystem);

    List<Step> steps = exportFile.getBuildSteps(context, new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "mkdir -p buck-out/gen",
            "cp " + projectFilesystem.resolve("example.html") + " buck-out/gen/fish"),
        steps,
        TestExecutionContext.newInstance());
    assertEquals(Paths.get("buck-out/gen/fish"), exportFile.getPathToOutputFile());
  }

  @Test
  public void shouldSetOutAndSrcAndNameParametersSeparately() throws IOException {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ExportFile exportFile = (ExportFile) ExportFileBuilder.newExportFileBuilder(target)
        .setSrc(new TestSourcePath("chips"))
        .setOut("fish")
        .build(new BuildRuleResolver(), projectFilesystem);

    List<Step> steps = exportFile.getBuildSteps(context, new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "mkdir -p buck-out/gen",
            "cp " + projectFilesystem.resolve("chips") + " buck-out/gen/fish"),
        steps,
        TestExecutionContext.newInstance());
    assertEquals(Paths.get("buck-out/gen/fish"), exportFile.getPathToOutputFile());
  }

  @Test
  public void shouldSetInputsFromSourcePaths() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    ExportFileBuilder builder = ExportFileBuilder.newExportFileBuilder(target)
        .setSrc(new TestSourcePath("chips"))
        .setOut("cake");

    ExportFile exportFile = (ExportFile) builder
        .build(new BuildRuleResolver());

    assertIterablesEquals(singleton(Paths.get("chips")), exportFile.getSource());

    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    FakeBuildRule rule = new FakeBuildRule(
        ExportFileDescription.TYPE,
        BuildTargetFactory.newInstance("//example:one"),
        new SourcePathResolver(ruleResolver));

    builder.setSrc(new BuildTargetSourcePath(projectFilesystem, rule.getBuildTarget()));
    exportFile = (ExportFile) builder.build(ruleResolver);
    assertTrue(Iterables.isEmpty(exportFile.getSource()));

    builder.setSrc(null);
    exportFile = (ExportFile) builder.build(new BuildRuleResolver());
    assertIterablesEquals(
        singleton(Paths.get("example.html")), exportFile.getSource());
  }

  @Test
  public void getOutputName() {
    ExportFile exportFile = (ExportFile) ExportFileBuilder.newExportFileBuilder(target)
        .setOut("cake")
        .build(new BuildRuleResolver());

    assertEquals("cake", exportFile.getOutputName());
  }

  @Test
  public void modifyingTheContentsOfTheFileChangesTheRuleKey() throws IOException {
    Path root = Files.createTempDirectory("root");
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem(root.toFile());
    Path temp = Files.createTempFile(root, "example", "file");
    temp.toFile().deleteOnExit();

    FileHashCache hashCache = new DefaultFileHashCache(filesystem);
    RuleKeyBuilderFactory ruleKeyFactory = new DefaultRuleKeyBuilderFactory(hashCache);

    Files.write(temp, "I like cheese".getBytes(UTF_8));

    ExportFileBuilder builder = ExportFileBuilder
        .newExportFileBuilder(BuildTargetFactory.newInstance("//some:file"))
        .setSrc(new TestSourcePath(MorePaths.relativize(root, temp).toString()));

    ExportFile rule = (ExportFile) builder.build(new BuildRuleResolver(), filesystem);

    RuleKey original = ruleKeyFactory
            .newInstance(rule, new SourcePathResolver(new BuildRuleResolver()))
            .build()
            .getTotalRuleKey();

    Files.write(temp, "I really like cheese".getBytes(UTF_8));

    // Create a new rule. The FileHashCache held by the existing rule will retain a reference to the
    // previous content of the file, so we need to create an identical rule.
    rule = (ExportFile) builder.build(new BuildRuleResolver(), filesystem);

    hashCache = new DefaultFileHashCache(filesystem);
    ruleKeyFactory = new DefaultRuleKeyBuilderFactory(hashCache);
    RuleKey refreshed = ruleKeyFactory
        .newInstance(rule, new SourcePathResolver(new BuildRuleResolver()))
        .build()
        .getTotalRuleKey();

    assertNotEquals(original, refreshed);
  }

  private BuildContext getBuildContext(File root) {
    return ImmutableBuildContext.builder()
        .setProjectFilesystem(new ProjectFilesystem(root.toPath()))
        .setArtifactCache(EasyMock.createMock(ArtifactCache.class))
        .setEventBus(BuckEventBusFactory.newInstance())
        .setClock(new DefaultClock())
        .setBuildId(new BuildId())
        .setJavaPackageFinder(
            new JavaPackageFinder() {
              @Override
              public Path findJavaPackageFolder(Path pathRelativeToProjectRoot) {
                return null;
              }

              @Override
              public String findJavaPackage(Path pathRelativeToProjectRoot) {
                return null;
              }

              @Override
              public String findJavaPackage(BuildTarget buildTarget) {
                return null;
              }
            })
        .setActionGraph(new ActionGraph(new MutableDirectedGraph<BuildRule>()))
        .setStepRunner(
            new StepRunner() {
              @Override
              public void runStep(Step step) throws StepFailedException {
                // Do nothing.
              }

              @Override
              public void runStepForBuildTarget(Step step, BuildTarget buildTarget)
                  throws StepFailedException {
                // Do nothing.
              }

              @Override
              public <T> ListenableFuture<T> runStepsAndYieldResult(
                  List<Step> steps, Callable<T> interpretResults, BuildTarget buildTarget) {
                return null;
              }

              @Override
              public void runStepsInParallelAndWait(List<Step> steps) throws StepFailedException {
                // Do nothing.
              }

              @Override
              public <T> ListenableFuture<Void> addCallback(
                  ListenableFuture<List<T>> allBuiltDeps, FutureCallback<List<T>> futureCallback) {
                // Do nothing.
                return Futures.immediateFuture(null);
              }
            })
        .build();
  }
}
