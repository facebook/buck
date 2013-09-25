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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

public class ExportFileTest {

  private BuildRuleParams params;
  private BuildContext context;
  private File root;

  @Before
  public void createFixtures() {
    params = new FakeBuildRuleParams(new BuildTarget("//", "example.html"));
    root = new File(".");
    context = getBuildContext(root);
  }

  @Test
  public void shouldSetSrcAndOutToNameParameterIfNeitherAreSet() throws IOException {
    ExportFile exportFile = new ExportFile(
        params, /* src */ Optional.<String>absent(), /* out */ Optional.<String>absent());

    List<Step> steps = exportFile.getBuildSteps(context, new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "mkdir -p buck-out/gen",
            "cp example.html buck-out/gen/example.html"),
        steps,
        TestExecutionContext.newInstance());
    assertEquals("buck-out/gen/example.html", exportFile.getPathToOutputFile());
  }

  @Test
  public void shouldSetOutToNameParamValueIfSrcIsSet() throws IOException {
    ExportFile exportFile = new ExportFile(
        params, /* src */ Optional.<String>absent(), /* out */ Optional.of("fish"));

    List<Step> steps = exportFile.getBuildSteps(context, new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "mkdir -p buck-out/gen",
            "cp example.html buck-out/gen/fish"),
        steps,
        TestExecutionContext.newInstance());
    assertEquals("buck-out/gen/fish", exportFile.getPathToOutputFile());
  }

  @Test
  public void shouldSetOutAndSrcAndNameParametersSeparately() throws IOException {
    ExportFile exportFile = new ExportFile(params,
        /* src */ Optional.of("chips"),
        /* out */ Optional.of("fish"));

    List<Step> steps = exportFile.getBuildSteps(context, new FakeBuildableContext());

    MoreAsserts.assertSteps(
        "The output directory should be created and then the file should be copied there.",
        ImmutableList.of(
            "mkdir -p buck-out/gen",
            "cp chips buck-out/gen/fish"),
        steps,
        TestExecutionContext.newInstance());
    assertEquals("buck-out/gen/fish", exportFile.getPathToOutputFile());
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
            // Do nothing
          }

          @Override
          public void runStepForBuildTarget(Step step, BuildTarget buildTarget)
              throws StepFailedException {
            // Do nothing
          }

          @Override
          public <T> ListenableFuture<T> runStepsAndYieldResult(
              List<Step> steps, Callable<T> interpretResults, BuildTarget buildTarget) {
            return null;
          }

          @Override
          public ListeningExecutorService getListeningExecutorService() {
            return null;
          }
        })
        .build();
  }
}
