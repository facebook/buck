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
import static org.junit.Assert.assertTrue;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

public class ExportFileTest {

  private BuildRuleParams params;
  private BuildContext context;

  @Before
  public void createFixtures() {
    params = new FakeBuildRuleParams(new BuildTarget("//", "example.html"));
    File root = new File(".");
    context = getBuildContext(root);
  }

  @Test
  public void shouldSetSrcAndOutToNameParameterIfNeitherAreSet() throws IOException {
    ExportFileDescription.Arg args = new ExportFileDescription().createUnpopulatedConstructorArg();
    args.out = Optional.absent();
    args.src = Optional.absent();
    ExportFile exportFile = new ExportFile(params, args);

    List<Step> steps = exportFile.getBuildSteps(context, new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "mkdir -p buck-out/gen",
            "cp example.html buck-out/gen/example.html"),
        steps,
        TestExecutionContext.newInstance());
    assertEquals(Paths.get("buck-out/gen/example.html"), exportFile.getPathToOutputFile());
  }

  @Test
  public void shouldSetOutToNameParamValueIfSrcIsSet() throws IOException {
    ExportFileDescription.Arg args = new ExportFileDescription().createUnpopulatedConstructorArg();
    args.out = Optional.of("fish");
    args.src = Optional.absent();
    ExportFile exportFile = new ExportFile(params, args);

    List<Step> steps = exportFile.getBuildSteps(context, new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "mkdir -p buck-out/gen",
            "cp example.html buck-out/gen/fish"),
        steps,
        TestExecutionContext.newInstance());
    assertEquals(Paths.get("buck-out/gen/fish"), exportFile.getPathToOutputFile());
  }

  @Test
  public void shouldSetOutAndSrcAndNameParametersSeparately() throws IOException {
    ExportFileDescription.Arg args = new ExportFileDescription().createUnpopulatedConstructorArg();
    args.src = Optional.of(new TestSourcePath("chips"));
    args.out = Optional.of("fish");
    ExportFile exportFile = new ExportFile(params, args);

    List<Step> steps = exportFile.getBuildSteps(context, new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "mkdir -p buck-out/gen",
            "cp chips buck-out/gen/fish"),
        steps,
        TestExecutionContext.newInstance());
    assertEquals(Paths.get("buck-out/gen/fish"), exportFile.getPathToOutputFile());
  }

  @Test
  public void shouldSetInputsFromSourcePaths() {
    ExportFileDescription.Arg args = new ExportFileDescription().createUnpopulatedConstructorArg();
    args.src = Optional.of(new TestSourcePath("chips"));
    args.out = Optional.of("cake");
    ExportFile exportFile = new ExportFile(params, args);

    assertIterablesEquals(singleton(Paths.get("chips")), exportFile.getInputsToCompareToOutput());

    FakeBuildRule rule = new FakeBuildRule(
        ExportFileDescription.TYPE,
        BuildTargetFactory.newInstance("//example:one"));
    args.src = Optional.of(
        new BuildRuleSourcePath(rule));
    exportFile = new ExportFile(params, args);
    assertTrue(Iterables.isEmpty(exportFile.getInputsToCompareToOutput()));

    args.src = Optional.absent();
    exportFile = new ExportFile(params, args);
    assertIterablesEquals(
        singleton(Paths.get("example.html")), exportFile.getInputsToCompareToOutput());
  }

  private BuildContext getBuildContext(File root) {
    return BuildContext.builder()
        .setProjectFilesystem(new ProjectFilesystem(root))
        .setArtifactCache(EasyMock.createMock(ArtifactCache.class))
        .setEventBus(BuckEventBusFactory.newInstance())
        .setAndroidBootclasspathForAndroidPlatformTarget(Optional.<AndroidPlatformTarget>absent())
        .setJavaPackageFinder(new JavaPackageFinder() {
          @Override
          public String findJavaPackageFolderForPath(String pathRelativeToProjectRoot) {
            return null;
          }

          @Override
          public String findJavaPackageForPath(String pathRelativeToProjectRoot) {
            return null;
          }
        })
        .setDependencyGraph(new DependencyGraph(new MutableDirectedGraph<BuildRule>()))
        .setStepRunner(new StepRunner() {
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
          public <T> void addCallback(
              ListenableFuture<List<T>> allBuiltDeps, FutureCallback<List<T>> futureCallback) {
            // Do nothing.
          }
        })
        .build();
  }
}
