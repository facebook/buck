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
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.dalvik.EstimateDexWeightStep;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.FakeJavaLibrary;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeOnDiskBuildInfo;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.ObjectMappers;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.easymock.EasyMock;
import org.junit.Test;

public class DexProducedFromJavaLibraryThatContainsClassFilesTest {

  @Test
  public void testGetBuildStepsWhenThereAreClassesToDex() throws IOException, InterruptedException {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();

    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    FakeJavaLibrary javaLibraryRule =
        new FakeJavaLibrary(
            BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:bar"),
            pathResolver,
            filesystem,
            ImmutableSortedSet.of()) {
          @Override
          public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
            return ImmutableSortedMap.of("com/example/Foo", HashCode.fromString("cafebabe"));
          }
        };
    resolver.addToIndex(javaLibraryRule);
    Path jarOutput =
        BuildTargets.getGenPath(filesystem, javaLibraryRule.getBuildTarget(), "%s.jar");
    javaLibraryRule.setOutputFile(jarOutput.toString());

    BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    Path dexOutput =
        BuildTargets.getGenPath(
            filesystem,
            javaLibraryRule.getBuildTarget().withFlavors(AndroidBinaryGraphEnhancer.DEX_FLAVOR),
            "%s.dex.jar");
    createFiles(filesystem, dexOutput.toString(), jarOutput.toString());

    BuildTarget buildTarget =
        BuildTargetFactory.newInstance(filesystem.getRootPath(), "//foo:bar#dex");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(buildTarget).setProjectFilesystem(filesystem).build();
    DexProducedFromJavaLibrary preDex = new DexProducedFromJavaLibrary(params, javaLibraryRule);
    List<Step> steps = preDex.getBuildSteps(context, buildableContext);

    AndroidPlatformTarget androidPlatformTarget = createMock(AndroidPlatformTarget.class);
    expect(androidPlatformTarget.getDxExecutable()).andStubReturn(Paths.get("/usr/bin/dx"));
    EasyMock.replay(androidPlatformTarget);

    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setAndroidPlatformTargetSupplier(Suppliers.ofInstance(androidPlatformTarget))
            .build();

    String expectedDxCommand =
        String.format(
            "%s --dex --no-optimize --force-jumbo --output %s %s",
            Paths.get("/usr/bin/dx"), filesystem.resolve(dexOutput), filesystem.resolve(jarOutput));
    MoreAsserts.assertSteps(
        "Generate bar.dex.jar.",
        ImmutableList.of(
            String.format("rm -f %s", filesystem.resolve(dexOutput)),
            String.format("mkdir -p %s", filesystem.resolve(dexOutput).getParent()),
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
    assertEquals(
        "The generated .dex.jar file should be in the set of recorded artifacts.",
        ImmutableSet.of(BuildTargets.getGenPath(filesystem, buildTarget, "%s.dex.jar")),
        buildableContext.getRecordedArtifacts());

    buildableContext.assertContainsMetadataMapping(
        DexProducedFromJavaLibrary.WEIGHT_ESTIMATE, "250");
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
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    DefaultJavaLibrary javaLibrary = JavaLibraryBuilder.createBuilder("//foo:bar").build(resolver);
    javaLibrary
        .getBuildOutputInitializer()
        .setBuildOutput(new JavaLibrary.Data(ImmutableSortedMap.of()));

    BuildContext context = FakeBuildContext.NOOP_CONTEXT;
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#dex");
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(buildTarget).setProjectFilesystem(projectFilesystem).build();
    DexProducedFromJavaLibrary preDex = new DexProducedFromJavaLibrary(params, javaLibrary);
    List<Step> steps = preDex.getBuildSteps(context, buildableContext);

    Path dexOutput = BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s.dex.jar");

    ExecutionContext executionContext = TestExecutionContext.newBuilder().build();

    MoreAsserts.assertSteps(
        "Do not generate a .dex.jar file.",
        ImmutableList.of(
            String.format("rm -f %s", projectFilesystem.resolve(dexOutput)),
            String.format("mkdir -p %s", projectFilesystem.resolve(dexOutput.getParent())),
            "record_empty_dx"),
        steps,
        executionContext);

    Step recordArtifactAndMetadataStep = steps.get(2);
    assertThat(recordArtifactAndMetadataStep.getShortName(), startsWith("record_"));
    int exitCode = recordArtifactAndMetadataStep.execute(executionContext).getExitCode();
    assertEquals(0, exitCode);
  }

  @Test
  public void testObserverMethods() throws Exception {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    DefaultJavaLibrary accumulateClassNames =
        JavaLibraryBuilder.createBuilder("//foo:bar").build(resolver);
    accumulateClassNames
        .getBuildOutputInitializer()
        .setBuildOutput(
            new JavaLibrary.Data(
                ImmutableSortedMap.of("com/example/Foo", HashCode.fromString("cafebabe"))));

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget).build();
    DexProducedFromJavaLibrary preDexWithClasses =
        new DexProducedFromJavaLibrary(params, accumulateClassNames);
    assertNull(preDexWithClasses.getSourcePathToOutput());
    assertEquals(
        BuildTargets.getGenPath(params.getProjectFilesystem(), buildTarget, "%s.dex.jar"),
        preDexWithClasses.getPathToDex());
  }

  private static <T> void initialize(
      InitializableFromDisk<T> initializableFromDisk, OnDiskBuildInfo onDiskBuildInfo)
      throws IOException {
    BuildOutputInitializer<T> buildOutputInitializer =
        initializableFromDisk.getBuildOutputInitializer();
    buildOutputInitializer.setBuildOutput(
        initializableFromDisk.initializeFromDisk(onDiskBuildInfo));
  }

  @Test
  public void getOutputDoesNotAccessWrappedJavaLibrary() throws Exception {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());

    JavaLibrary javaLibrary =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .build(ruleResolver);

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:target")).build();
    DexProducedFromJavaLibrary dexProducedFromJavaLibrary =
        new DexProducedFromJavaLibrary(params, javaLibrary);

    FakeOnDiskBuildInfo onDiskBuildInfo =
        new FakeOnDiskBuildInfo()
            .putMetadata(DexProducedFromJavaLibrary.WEIGHT_ESTIMATE, "0")
            .putMetadata(
                DexProducedFromJavaLibrary.CLASSNAMES_TO_HASHES,
                ObjectMappers.WRITER.writeValueAsString(ImmutableMap.<String, String>of()));
    initialize(dexProducedFromJavaLibrary, onDiskBuildInfo);

    assertFalse(dexProducedFromJavaLibrary.hasOutput());
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
