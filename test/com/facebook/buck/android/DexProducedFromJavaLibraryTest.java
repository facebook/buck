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

package com.facebook.buck.android;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.Test;

public class DexProducedFromJavaLibraryTest {
  @Test
  public void testGetBuildStepsWithD8MethodsDesugar() throws Exception {

    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));

    FakeJavaLibrary javaLibRule =
        new FakeJavaLibrary(
            BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:lib"),
            filesystem,
            ImmutableSortedSet.of());
    graphBuilder.addToIndex(javaLibRule);
    Path jarLibOutput =
        BuildTargetPaths.getGenPath(filesystem, javaLibRule.getBuildTarget(), "%s.jar");
    javaLibRule.setOutputFile(jarLibOutput.toString());

    FakeJavaLibrary javaBarRule =
        new FakeJavaLibrary(
            BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:bar"),
            filesystem,
            ImmutableSortedSet.of(javaLibRule)) {
          @Override
          public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
            return ImmutableSortedMap.of("com/example/Foo", HashCode.fromString("cafebabe"));
          }
        };
    graphBuilder.addToIndex(javaBarRule);
    Path jarBarOutput =
        BuildTargetPaths.getGenPath(filesystem, javaBarRule.getBuildTarget(), "%s.jar");
    javaBarRule.setOutputFile(jarBarOutput.toString());

    BuildContext context =
        FakeBuildContext.withSourcePathResolver(pathResolver)
            .withBuildCellRootPath(filesystem.getRootPath());
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    Path dexOutput =
        BuildTargetPaths.getGenPath(
            filesystem,
            javaBarRule.getBuildTarget().withFlavors(AndroidBinaryGraphEnhancer.D8_FLAVOR),
            "%s.dex.jar");
    createFiles(filesystem, dexOutput.toString(), jarLibOutput.toString(), jarBarOutput.toString());

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:bar#d8");

    BuildRuleParams params = TestBuildRuleParams.create();

    ImmutableSortedSet<BuildRule> desugarDeps = ImmutableSortedSet.of(javaLibRule);

    DexProducedFromJavaLibrary preDex =
        new DexProducedFromJavaLibrary(
            buildTarget,
            filesystem,
            TestAndroidPlatformTargetFactory.create(),
            params,
            javaBarRule,
            DxStep.D8,
            1,
            desugarDeps);
    List<Step> steps = preDex.getBuildSteps(context, buildableContext);
    DxStep dxStep = null;
    for (Step step : steps) {
      if (step instanceof DxStep) {
        dxStep = (DxStep) step;
        break;
      }
    }
    assertNotNull(dxStep);
    assertThat(
        dxStep.classpathFiles,
        Matchers.hasItem(
            context.getSourcePathResolver().getAbsolutePath(javaLibRule.getSourcePathToOutput())));
  }

  private void createFiles(ProjectFilesystem filesystem, String... paths) throws IOException {
    Path root = filesystem.getRootPath();
    for (String path : paths) {
      Path resolved = root.resolve(path);
      Files.createDirectories(resolved.getParent());
      Files.write(resolved, "".getBytes(UTF_8));
    }
  }
}
