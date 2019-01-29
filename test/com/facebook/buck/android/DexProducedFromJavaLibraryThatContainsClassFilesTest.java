/*
 * Copyright 2013-present Facebook, Inc.
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
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.impl.testutil.SimpleTool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
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
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    FakeJavaLibrary javaLibraryRule =
        new FakeJavaLibrary(
            BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:bar"),
            filesystem,
            ImmutableSortedSet.of()) {
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
        FakeBuildContext.withSourcePathResolver(pathResolver)
            .withBuildCellRootPath(filesystem.getRootPath());
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    Path dexOutput =
        BuildTargetPaths.getGenPath(
            filesystem,
            javaLibraryRule.getBuildTarget().withFlavors(AndroidBinaryGraphEnhancer.DEX_FLAVOR),
            "%s.dex.jar");
    createFiles(filesystem, dexOutput.toString(), jarOutput.toString());

    AndroidPlatformTarget androidPlatformTarget =
        AndroidPlatformTarget.of(
            "android",
            Paths.get(""),
            Collections.emptyList(),
            () -> new SimpleTool(""),
            () -> new SimpleTool(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get("/usr/bin/dx"),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""),
            Paths.get(""));

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:bar#dex");
    BuildRuleParams params = TestBuildRuleParams.create();
    DexProducedFromJavaLibrary preDex =
        new DexProducedFromJavaLibrary(
            buildTarget, filesystem, androidPlatformTarget, params, javaLibraryRule, DxStep.DX);
    List<Step> steps = preDex.getBuildSteps(context, buildableContext);

    ExecutionContext executionContext = TestExecutionContext.newBuilder().build();

    String expectedDxCommand =
        String.format(
            "%s --dex --no-optimize --force-jumbo --output %s %s",
            Paths.get("/usr/bin/dx"), filesystem.resolve(dexOutput), filesystem.resolve(jarOutput));
    MoreAsserts.assertSteps(
        "Generate bar.dex.jar.",
        ImmutableList.of(
            String.format("rm -f %s", dexOutput),
            String.format("mkdir -p %s", dexOutput.getParent()),
            "estimate_dex_weight",
            "(cd " + filesystem.getRootPath() + " && " + expectedDxCommand + ")",
            String.format("zip-scrub %s", filesystem.resolve(dexOutput)),
            "record_dx_success"),
        steps,
        executionContext);

    ((EstimateDexWeightStep) steps.get(2)).setWeightEstimateForTesting(250);
    Step recordArtifactAndMetadataStep = steps.get(5);
    int exitCode = recordArtifactAndMetadataStep.execute(executionContext).getExitCode();
    assertEquals(0, exitCode);
    MoreAsserts.assertContainsOne(
        "The generated .dex.jar file should be in the set of recorded artifacts.",
        buildableContext.getRecordedArtifacts(),
        BuildTargetPaths.getGenPath(filesystem, buildTarget, "%s.dex.jar"));

    BuildOutputInitializer<DexProducedFromJavaLibrary.BuildOutput> outputInitializer =
        preDex.getBuildOutputInitializer();
    outputInitializer.initializeFromDisk(pathResolver);
    assertEquals(250, outputInitializer.getBuildOutput().weightEstimate);
  }

  private void createFiles(ProjectFilesystem filesystem, String... paths) throws IOException {
    Path root = filesystem.getRootPath();
    for (String path : paths) {
      Path resolved = root.resolve(path);
      Files.createDirectories(resolved.getParent());
      Files.write(resolved, "".getBytes(UTF_8));
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

    BuildContext context = FakeBuildContext.NOOP_CONTEXT;
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#dex");
    BuildRuleParams params = TestBuildRuleParams.create();
    DexProducedFromJavaLibrary preDex =
        new DexProducedFromJavaLibrary(
            buildTarget,
            projectFilesystem,
            TestAndroidPlatformTargetFactory.create(),
            params,
            javaLibrary,
            DxStep.DX);
    List<Step> steps = preDex.getBuildSteps(context, buildableContext);

    Path dexOutput = BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s.dex.jar");

    ExecutionContext executionContext = TestExecutionContext.newBuilder().build();

    MoreAsserts.assertSteps(
        "Do not generate a .dex.jar file.",
        ImmutableList.of(
            String.format("rm -f %s", dexOutput),
            String.format("mkdir -p %s", dexOutput.getParent()),
            "record_empty_dx"),
        steps,
        executionContext);

    Step recordArtifactAndMetadataStep = steps.get(2);
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
    BuildRuleParams params = TestBuildRuleParams.create();
    DexProducedFromJavaLibrary preDexWithClasses =
        new DexProducedFromJavaLibrary(
            buildTarget,
            projectFilesystem,
            TestAndroidPlatformTargetFactory.create(),
            params,
            accumulateClassNames,
            DxStep.DX);
    assertNull(preDexWithClasses.getSourcePathToOutput());
    assertEquals(
        BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s.dex.jar"),
        preDexWithClasses.getPathToDex());
  }

  @Test
  public void testComputeAbiKey() {
    ImmutableSortedMap<String, HashCode> classNamesAndHashes =
        ImmutableSortedMap.of(
            "com/example/Foo", HashCode.fromString("e4fccb7520b7795e632651323c63217c9f59f72a"),
            "com/example/Bar", HashCode.fromString("087b7707a5f8e0a2adf5652e3cd2072d89a197dc"),
            "com/example/Baz", HashCode.fromString("62b1c2510840c0de55c13f66065a98a719be0f19"));
    String observedSha1 = DexProducedFromJavaLibrary.computeAbiKey(classNamesAndHashes).getHash();

    String expectedSha1 =
        Hashing.sha1()
            .newHasher()
            .putUnencodedChars("com/example/Bar")
            .putByte((byte) 0)
            .putUnencodedChars("087b7707a5f8e0a2adf5652e3cd2072d89a197dc")
            .putByte((byte) 0)
            .putUnencodedChars("com/example/Baz")
            .putByte((byte) 0)
            .putUnencodedChars("62b1c2510840c0de55c13f66065a98a719be0f19")
            .putByte((byte) 0)
            .putUnencodedChars("com/example/Foo")
            .putByte((byte) 0)
            .putUnencodedChars("e4fccb7520b7795e632651323c63217c9f59f72a")
            .putByte((byte) 0)
            .hash()
            .toString();
    assertEquals(expectedSha1, observedSha1);
  }
}
