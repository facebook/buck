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
import static org.easymock.EasyMock.expect;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.dalvik.EstimateLinearAllocStep;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.java.FakeJavaLibrary;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeOnDiskBuildInfo;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class DexProducedFromJavaLibraryThatContainsClassFilesTest extends EasyMockSupport {

  @Test
  public void testGetBuildStepsWhenThereAreClassesToDex() throws IOException, InterruptedException {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    FakeJavaLibrary javaLibraryRule = new FakeJavaLibrary(
        BuildTarget.builder("//foo", "bar").build(), pathResolver) {
      @Override
      public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
        return ImmutableSortedMap.of("com/example/Foo", HashCode.fromString("cafebabe"));
      }

      @Override
      public Sha1HashCode getAbiKey() {
        return Sha1HashCode.of("f7f34ed13b881c6c6f663533cde4a436ea84435e");
      }
    };
    javaLibraryRule.setOutputFile("buck-out/gen/foo/bar.jar");

    BuildContext context = createMock(BuildContext.class);
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    replayAll();

    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/home/user");
    createFiles(
        filesystem,
        "buck-out/gen/foo/bar#dex.dex.jar",
        "buck-out/gen/foo/bar.jar");

    BuildTarget buildTarget = BuildTarget
        .builder("//foo", "bar")
        .addFlavors(ImmutableFlavor.of("dex"))
        .build();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget)
        .setProjectFilesystem(filesystem)
        .build();
    DexProducedFromJavaLibrary preDex =
        new DexProducedFromJavaLibrary(params, pathResolver, javaLibraryRule);
    List<Step> steps = preDex.getBuildSteps(context, buildableContext);

    verifyAll();
    resetAll();

    AndroidPlatformTarget androidPlatformTarget = createMock(AndroidPlatformTarget.class);
    expect(androidPlatformTarget.getDxExecutable()).andReturn(Paths.get("/usr/bin/dx"));

    replayAll();

    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setAndroidPlatformTargetSupplier(Suppliers.ofInstance(androidPlatformTarget))
        .build();

    String expectedDxCommand = String.format(
        "%s --dex --no-optimize --force-jumbo --output %s %s",
        Paths.get("/usr/bin/dx"),
        Paths.get("/home/user/buck-out/gen/foo/bar#dex.dex.jar"),
        Paths.get("/home/user/buck-out/gen/foo/bar.jar"));
    MoreAsserts.assertSteps("Generate bar.dex.jar.",
        ImmutableList.of(
            String.format("rm -f %s", Paths.get("/home/user/buck-out/gen/foo/bar#dex.dex.jar")),
            String.format("mkdir -p %s", Paths.get("/home/user/buck-out/gen/foo")),
            "estimate_linear_alloc",
            "(cd /home/user && " + expectedDxCommand + ")",
            "zip-scrub buck-out/gen/foo/bar#dex.dex.jar",
            "record_dx_success"),
        steps,
        executionContext);

    verifyAll();
    resetAll();

    replayAll();

    ((EstimateLinearAllocStep) steps.get(2)).setLinearAllocEstimateForTesting(250);
    Step recordArtifactAndMetadataStep = steps.get(5);
    int exitCode = recordArtifactAndMetadataStep.execute(executionContext);
    assertEquals(0, exitCode);
    assertEquals("The generated .dex.jar file should be in the set of recorded artifacts.",
        ImmutableSet.of(Paths.get("buck-out/gen/foo/bar#dex.dex.jar")),
        buildableContext.getRecordedArtifacts());

    buildableContext.assertContainsMetadataMapping(
        DexProducedFromJavaLibrary.LINEAR_ALLOC_KEY_ON_DISK_METADATA, "250");

    verifyAll();
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
  public void testGetBuildStepsWhenThereAreNoClassesToDex()
      throws IOException, InterruptedException {
    JavaLibrary javaLibrary = createMock(JavaLibrary.class);
    expect(javaLibrary.getClassNamesToHashes()).andReturn(
        ImmutableSortedMap.<String, HashCode>of());

    BuildContext context = createMock(BuildContext.class);
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);

    replayAll();

    BuildTarget buildTarget = BuildTarget.builder("//foo", "bar").build();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget)
        .setProjectFilesystem(projectFilesystem)
        .build();
    DexProducedFromJavaLibrary preDex =
        new DexProducedFromJavaLibrary(
            params,
            new SourcePathResolver(new BuildRuleResolver()),
            javaLibrary);
    List<Step> steps = preDex.getBuildSteps(context, buildableContext);

    verifyAll();
    resetAll();

    expect(projectFilesystem.resolve(Paths.get("buck-out/gen/foo")))
        .andReturn(Paths.get("/home/user/buck-out/gen/foo"));
    expect(projectFilesystem.resolve(Paths.get("buck-out/gen/foo/bar.dex.jar")))
        .andReturn(Paths.get("/home/user/buck-out/gen/foo/bar.dex.jar"));
    replayAll();

    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .build();

    MoreAsserts.assertSteps("Do not generate a .dex.jar file.",
        ImmutableList.of(
          String.format("rm -f %s", Paths.get("/home/user/buck-out/gen/foo/bar.dex.jar")),
          String.format("mkdir -p %s", Paths.get("/home/user/buck-out/gen/foo")),
          "record_empty_dx"),
        steps,
        executionContext);

    verifyAll();
    resetAll();

    replayAll();

    Step recordArtifactAndMetadataStep = steps.get(2);
    assertThat(recordArtifactAndMetadataStep.getShortName(), startsWith("record_"));
    int exitCode = recordArtifactAndMetadataStep.execute(executionContext);
    assertEquals(0, exitCode);

    verifyAll();
  }

  @Test
  public void testObserverMethods() {
    JavaLibrary accumulateClassNames = createMock(JavaLibrary.class);
    expect(accumulateClassNames.getClassNamesToHashes())
        .andReturn(ImmutableSortedMap.of("com/example/Foo", HashCode.fromString("cafebabe")))
        .anyTimes();

    replayAll();

    BuildTarget buildTarget = BuildTarget.builder("//foo", "bar").build();
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(buildTarget).build();
    DexProducedFromJavaLibrary preDexWithClasses =
        new DexProducedFromJavaLibrary(
            params,
            new SourcePathResolver(new BuildRuleResolver()),
            accumulateClassNames);
    assertNull(preDexWithClasses.getPathToOutput());
    assertEquals(Paths.get("buck-out/gen/foo/bar.dex.jar"), preDexWithClasses.getPathToDex());

    verifyAll();
  }

  private static <T> void initialize(
      InitializableFromDisk<T> initializableFromDisk,
      OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    BuildOutputInitializer<T> buildOutputInitializer =
        initializableFromDisk.getBuildOutputInitializer();
    buildOutputInitializer.setBuildOutput(
        initializableFromDisk.initializeFromDisk(onDiskBuildInfo));
  }

  @Test
  public void getOutputDoesNotAccessWrappedJavaLibrary() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    JavaLibrary javaLibrary =
        (JavaLibrary) JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib"))
            .build(ruleResolver);

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:target"))
            .build();
    DexProducedFromJavaLibrary dexProducedFromJavaLibrary =
        new DexProducedFromJavaLibrary(params, pathResolver, javaLibrary);

    ObjectMapper mapper = new ObjectMapper();
    FakeOnDiskBuildInfo onDiskBuildInfo =
        new FakeOnDiskBuildInfo()
            .putMetadata(
                DexProducedFromJavaLibrary.LINEAR_ALLOC_KEY_ON_DISK_METADATA,
                "0")
            .putMetadata(
                DexProducedFromJavaLibrary.CLASSNAMES_TO_HASHES,
                mapper.writeValueAsString(ImmutableMap.<String, String>of()));
    initialize(dexProducedFromJavaLibrary, onDiskBuildInfo);

    assertFalse(dexProducedFromJavaLibrary.hasOutput());
  }

  @Test
  public void testComputeAbiKey() {
    ImmutableSortedMap<String, HashCode> classNamesAndHashes = ImmutableSortedMap.of(
        "com/example/Foo", HashCode.fromString("e4fccb7520b7795e632651323c63217c9f59f72a"),
        "com/example/Bar", HashCode.fromString("087b7707a5f8e0a2adf5652e3cd2072d89a197dc"),
        "com/example/Baz", HashCode.fromString("62b1c2510840c0de55c13f66065a98a719be0f19"));
    String observedSha1 = DexProducedFromJavaLibrary
        .computeAbiKey(classNamesAndHashes)
        .getHash();

    String expectedSha1 = Hashing.sha1().newHasher()
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
