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

import static org.easymock.EasyMock.expect;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.dalvik.EstimateLinearAllocStep;
import com.facebook.buck.java.FakeJavaLibraryRule;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

public class DexProducedFromJavaLibraryThatContainsClassFilesTest extends EasyMockSupport {

  @Test
  public void testGetBuildStepsWhenThereAreClassesToDex() throws IOException {
    FakeJavaLibraryRule javaLibraryRule = new FakeJavaLibraryRule(new BuildTarget("//foo", "bar")) {
      @Override
      public ImmutableSortedMap<String, HashCode> getClassNamesToHashes() {
        return ImmutableSortedMap.of("com/example/Foo", HashCode.fromString("cafebabe"));
      }

      @Override
      public Sha1HashCode getAbiKey() {
        return new Sha1HashCode("f7f34ed13b881c6c6f663533cde4a436ea84435e");
      }
    };
    javaLibraryRule.setOutputFile("buck-out/gen/foo/bar.jar");

    BuildContext context = createMock(BuildContext.class);
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    replayAll();

    BuildTarget buildTarget = new BuildTarget("//foo", "bar", "dex");
    DexProducedFromJavaLibraryThatContainsClassFiles preDex =
        new DexProducedFromJavaLibraryThatContainsClassFiles(buildTarget, javaLibraryRule);
    List<Step> steps = preDex.getBuildSteps(context, buildableContext);

    verifyAll();
    resetAll();

    AndroidPlatformTarget androidPlatformTarget = createMock(AndroidPlatformTarget.class);
    expect(androidPlatformTarget.getDxExecutable()).andReturn(new File("/usr/bin/dx"));

    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem.resolve(Paths.get("buck-out/gen/foo")))
        .andReturn(Paths.get("/home/user/buck-out/gen/foo"));
    expect(projectFilesystem.resolve(Paths.get("buck-out/gen/foo/bar#dex.dex.jar")))
        .andReturn(Paths.get("/home/user/buck-out/gen/foo/bar#dex.dex.jar"));
    expect(projectFilesystem.resolve(Paths.get("buck-out/gen/foo/bar.jar")))
        .andReturn(Paths.get("/home/user/buck-out/gen/foo/bar.jar"));
    replayAll();

    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setAndroidPlatformTarget(Optional.of(androidPlatformTarget))
        .setProjectFilesystem(projectFilesystem)
        .build();

    String expectedDxCommand = "/usr/bin/dx" +
        " --dex --no-optimize --force-jumbo --output buck-out/gen/foo/bar#dex.dex.jar " +
        "/home/user/buck-out/gen/foo/bar.jar";
    MoreAsserts.assertSteps("Generate bar.dex.jar.",
        ImmutableList.of(
          "rm -f /home/user/buck-out/gen/foo/bar#dex.dex.jar",
          "mkdir -p /home/user/buck-out/gen/foo",
          "estimate_linear_alloc",
          expectedDxCommand,
          "record_dx_success"),
        steps,
        executionContext);

    verifyAll();
    resetAll();

    replayAll();

    ((EstimateLinearAllocStep) steps.get(2)).setLinearAllocEstimateForTesting(250);
    Step recordArtifactAndMetadataStep = steps.get(4);
    int exitCode = recordArtifactAndMetadataStep.execute(executionContext);
    assertEquals(0, exitCode);
    assertEquals("The generated .dex.jar file should be in the set of recorded artifacts.",
        ImmutableSet.of(Paths.get("buck-out/gen/foo/bar#dex.dex.jar")),
        buildableContext.getRecordedArtifacts());

    buildableContext.assertContainsMetadataMapping(
        DexProducedFromJavaLibraryThatContainsClassFiles.LINEAR_ALLOC_KEY_ON_DISK_METADATA, "250");

    verifyAll();
  }

  @Test
  public void testGetBuildStepsWhenThereAreNoClassesToDex() throws IOException {
    JavaLibraryRule javaLibrary = createMock(JavaLibraryRule.class);
    expect(javaLibrary.getClassNamesToHashes()).andReturn(
        ImmutableSortedMap.<String, HashCode>of());

    BuildContext context = createMock(BuildContext.class);
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    replayAll();

    BuildTarget buildTarget = new BuildTarget("//foo", "bar");
    DexProducedFromJavaLibraryThatContainsClassFiles preDex =
        new DexProducedFromJavaLibraryThatContainsClassFiles(buildTarget, javaLibrary);
    List<Step> steps = preDex.getBuildSteps(context, buildableContext);

    verifyAll();
    resetAll();

    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem.resolve(Paths.get("buck-out/gen/foo")))
        .andReturn(Paths.get("/home/user/buck-out/gen/foo"));
    expect(projectFilesystem.resolve(Paths.get("buck-out/gen/foo/bar.dex.jar")))
        .andReturn(Paths.get("/home/user/buck-out/gen/foo/bar.dex.jar"));
    replayAll();

    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();

    MoreAsserts.assertSteps("Do not generate a .dex.jar file.",
        ImmutableList.of(
          "rm -f /home/user/buck-out/gen/foo/bar.dex.jar",
          "mkdir -p /home/user/buck-out/gen/foo",
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
    JavaLibraryRule accumulateClassNames = createMock(JavaLibraryRule.class);
    expect(accumulateClassNames.getClassNamesToHashes())
        .andReturn(ImmutableSortedMap.of("com/example/Foo", HashCode.fromString("cafebabe")))
        .anyTimes();

    replayAll();

    BuildTarget buildTarget = new BuildTarget("//foo", "bar");
    DexProducedFromJavaLibraryThatContainsClassFiles preDexWithClasses =
        new DexProducedFromJavaLibraryThatContainsClassFiles(buildTarget, accumulateClassNames);
    assertNull(preDexWithClasses.getPathToOutputFile());
    assertTrue(Iterables.isEmpty(preDexWithClasses.getInputsToCompareToOutput()));
    assertEquals(Paths.get("buck-out/gen/foo/bar.dex.jar"), preDexWithClasses.getPathToDex());
    assertTrue(preDexWithClasses.hasOutput());

    verifyAll();
  }

  @Test
  public void testComputeAbiKey() {
    ImmutableSortedMap<String, HashCode> classNamesAndHashes = ImmutableSortedMap.of(
        "com/example/Foo", HashCode.fromString("e4fccb7520b7795e632651323c63217c9f59f72a"),
        "com/example/Bar", HashCode.fromString("087b7707a5f8e0a2adf5652e3cd2072d89a197dc"),
        "com/example/Baz", HashCode.fromString("62b1c2510840c0de55c13f66065a98a719be0f19")
    );
    String observedSha1 = DexProducedFromJavaLibraryThatContainsClassFiles
        .computeAbiKey(classNamesAndHashes)
        .getHash();

    String expectedSha1 = Hashing.sha1().newHasher()
        .putUnencodedChars("com/example/Bar")
        .putByte((byte)0)
        .putUnencodedChars("087b7707a5f8e0a2adf5652e3cd2072d89a197dc")
        .putByte((byte)0)
        .putUnencodedChars("com/example/Baz")
        .putByte((byte)0)
        .putUnencodedChars("62b1c2510840c0de55c13f66065a98a719be0f19")
        .putByte((byte)0)
        .putUnencodedChars("com/example/Foo")
        .putByte((byte)0)
        .putUnencodedChars("e4fccb7520b7795e632651323c63217c9f59f72a")
        .putByte((byte)0)
        .hash()
        .toString();
    assertEquals(expectedSha1, observedSha1);
  }
}
