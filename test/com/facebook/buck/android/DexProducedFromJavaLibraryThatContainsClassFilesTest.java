/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.dalvik.EstimateDexWeightStep;
import com.facebook.buck.android.toolchain.AndroidPlatformTarget;
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.toolchain.tool.impl.testutil.SimpleTool;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.FakeJavaClassHashesProvider;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.rules.modern.DefaultOutputPathResolver;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class DexProducedFromJavaLibraryThatContainsClassFilesTest {

  @Test
  public void testGetBuildStepsWhenThereAreClassesToDex() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeJavaLibrary javaLibraryRule =
        new FakeJavaLibrary(
            BuildTargetFactory.newInstance("//foo:bar"), filesystem, ImmutableSortedSet.of()) {
          @Override
          public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
            return ImmutableSortedMap.of("com/example/Foo", HashCode.fromString("cafebabe"));
          }
        };
    graphBuilder.addToIndex(javaLibraryRule);
    Path jarOutput =
        BuildTargetPaths.getGenPath(filesystem, javaLibraryRule.getBuildTarget(), "%s.jar");
    javaLibraryRule.setOutputFile(jarOutput.toString());

    BuildContext context =
        FakeBuildContext.withSourcePathResolver(graphBuilder.getSourcePathResolver())
            .withBuildCellRootPath(filesystem.getRootPath().getPath());
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    Path dexOutput =
        BuildTargetPaths.getGenPath(
            filesystem,
            javaLibraryRule.getBuildTarget().withFlavors(AndroidBinaryGraphEnhancer.DEX_FLAVOR),
            "%s/dex.jar");
    createFiles(filesystem, dexOutput.toString(), jarOutput.toString());

    AndroidPlatformTarget androidPlatformTarget =
        AndroidPlatformTarget.of(
            "android",
            Paths.get(""),
            Collections.emptyList(),
            () -> new SimpleTool(""),
            new ConstantToolProvider(new SimpleTool("")),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get("/usr/bin/dx"),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""));

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#dex");
    DexProducedFromJavaLibrary preDex =
        new DexProducedFromJavaLibrary(
            buildTarget, filesystem, graphBuilder, androidPlatformTarget, javaLibraryRule);
    List<Step> steps = preDex.getBuildSteps(context, buildableContext);
    steps = steps.subList(4, steps.size());

    ExecutionContext executionContext = TestExecutionContext.newBuilder().build();

    String expectedDxCommand =
        String.format(
            "%s --dex --no-optimize --force-jumbo --output %s %s",
            Paths.get("/usr/bin/dx"), filesystem.resolve(dexOutput), filesystem.resolve(jarOutput));
    MoreAsserts.assertSteps(
        "Generate bar.dex.jar.",
        ImmutableList.of(
            "estimate_dex_weight",
            "(cd " + filesystem.getRootPath() + " && " + expectedDxCommand + ")",
            String.format("zip-scrub %s", filesystem.resolve(dexOutput)),
            "record_dx_success"),
        steps,
        executionContext);

    ((EstimateDexWeightStep) Iterables.getFirst(steps, null)).setWeightEstimateForTesting(250);
    Step recordArtifactAndMetadataStep = Iterables.getLast(steps);
    int exitCode = recordArtifactAndMetadataStep.execute(executionContext).getExitCode();
    assertEquals(0, exitCode);
    MoreAsserts.assertContainsOne(
        "The folder that contain generated .dex.jar file should be in the set of recorded artifacts.",
        buildableContext.getRecordedArtifacts(),
        BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s"));

    BuildOutputInitializer<DexProducedFromJavaLibrary.BuildOutput> outputInitializer =
        preDex.getBuildOutputInitializer();
    outputInitializer.initializeFromDisk(graphBuilder.getSourcePathResolver());
    assertEquals(250, outputInitializer.getBuildOutput().getWeightEstimate());
  }

  private void createFiles(ProjectFilesystem filesystem, String... paths) throws IOException {
    AbsPath root = filesystem.getRootPath();
    for (String path : paths) {
      AbsPath resolved = root.resolve(path);
      Files.createDirectories(resolved.getParent().getPath());
      Files.write(resolved.getPath(), "".getBytes(UTF_8));
    }
  }

  @Test
  public void testGetBuildStepsWhenThereAreNoClassesToDex() throws Exception {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    DefaultJavaLibrary javaLibrary =
        JavaLibraryBuilder.createBuilder("//foo:bar").build(graphBuilder);
    javaLibrary
        .getBuildOutputInitializer()
        .setBuildOutputForTests(new JavaLibrary.Data(ImmutableSortedMap.of()));
    javaLibrary.setJavaClassHashesProvider(new FakeJavaClassHashesProvider());

    BuildContext context = FakeBuildContext.NOOP_CONTEXT;
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#dex");
    DexProducedFromJavaLibrary preDex =
        new DexProducedFromJavaLibrary(
            buildTarget,
            projectFilesystem,
            graphBuilder,
            TestAndroidPlatformTargetFactory.create(),
            javaLibrary);
    List<Step> steps = preDex.getBuildSteps(context, buildableContext);

    ExecutionContext executionContext = TestExecutionContext.newBuilder().build();

    Step recordArtifactAndMetadataStep = Iterables.getLast(steps);
    assertThat(recordArtifactAndMetadataStep.getShortName(), startsWith("record_"));
    int exitCode = recordArtifactAndMetadataStep.execute(executionContext).getExitCode();
    assertEquals(0, exitCode);
  }

  @Test
  public void testObserverMethods() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    DefaultJavaLibrary accumulateClassNames =
        JavaLibraryBuilder.createBuilder("//foo:bar").build(graphBuilder);
    accumulateClassNames
        .getBuildOutputInitializer()
        .setBuildOutputForTests(
            new JavaLibrary.Data(
                ImmutableSortedMap.of("com/example/Foo", HashCode.fromString("cafebabe"))));

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    DexProducedFromJavaLibrary preDexWithClasses =
        new DexProducedFromJavaLibrary(
            buildTarget,
            projectFilesystem,
            graphBuilder,
            TestAndroidPlatformTargetFactory.create(),
            accumulateClassNames);
    assertNull(preDexWithClasses.getSourcePathToOutput());
    OutputPathResolver outputPathResolver =
        new DefaultOutputPathResolver(projectFilesystem, buildTarget);
    assertEquals(
        BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s__/dex.jar"),
        outputPathResolver.resolvePath(preDexWithClasses.getPathToDex()));
  }
}
