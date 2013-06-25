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

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.ArtifactCache;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.rules.JavaPackageFinder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepFailedException;
import com.facebook.buck.step.StepRunner;
import com.facebook.buck.step.fs.MkdirAndSymlinkFileStep;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;

public class ExportFileRuleTest {

  private BuildRuleParams params;
  private BuildContext context;
  private File root;

  @Before
  public void createFixtures() {
    BuildTarget target = BuildTargetFactory.newInstance("//:example.html");
    params = new BuildRuleParams(target,
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSet.<BuildTargetPattern>of(),
        /* pathRelativizer */ Functions.<String>identity());
    root = new File(".");
    context = getBuildContext(root);
  }

  @Test
  public void shouldSetSrcAndOutToNameParameterIfNeitherAreSet() throws IOException {
    ExportFileRule rule = new ExportFileRule(
        params, Optional.<String>absent(), Optional.<String>absent());

    List<Step> steps = rule.buildInternal(context);

    MkdirAndSymlinkFileStep expected = new MkdirAndSymlinkFileStep(
        "example.html",
        BuckConstant.GEN_DIR + "/example.html");
    assertEquals(ImmutableList.of(expected), steps);
  }

  @Test
  public void shouldSetOutToNameParamValueIfSrcIsSet() throws IOException {
    ExportFileRule rule = new ExportFileRule(
        params, Optional.<String>absent(), Optional.of("fish"));

    List<Step> steps = rule.buildInternal(context);

    MkdirAndSymlinkFileStep expected = new MkdirAndSymlinkFileStep(
        "example.html",
        BuckConstant.GEN_DIR + "/fish");
    assertEquals(ImmutableList.of(expected), steps);
  }

  @Test
  public void shouldSetOutAndSrcAndNameParametersSeparately() throws IOException {
    ExportFileRule rule = new ExportFileRule(params, Optional.of("chips"), Optional.of("fish"));

    List<Step> steps = rule.buildInternal(context);

    MkdirAndSymlinkFileStep expected = new MkdirAndSymlinkFileStep(
        "chips",
        BuckConstant.GEN_DIR + "/fish");
    assertEquals(ImmutableList.of(expected), steps);
  }

  private BuildContext getBuildContext(File root) {
    return BuildContext.builder()
        .setProjectRoot(root)
        .setProjectFilesystem(new ProjectFilesystem(root))
        .setArtifactCache(EasyMock.createMock(ArtifactCache.class))
        .setEventBus(new EventBus())
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
