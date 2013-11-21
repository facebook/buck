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

package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.java.AccumulateClassNames.ClassNamesAndAbiKey;
import com.facebook.buck.java.abi.AbiWriterProtocol;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeOnDiskBuildInfo;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class AccumulateClassNamesTest extends EasyMockSupport {

  @Test
  public void testObserversForAccumulateClassNames() throws IOException {
    // Create a JavaLibraryRule with classes to accumulate.
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    JavaLibraryRule javaLibraryRule = buildRuleResolver.buildAndAddToIndex(PrebuiltJarRule
        .newPrebuiltJarRuleBuilder(new FakeAbstractBuildRuleBuilderParams())
        .setBuildTarget(new BuildTarget("//foo/bar", "jar"))
        .setBinaryJar("foo/bar/example.jar"));

    // Create the Builder.
    AbstractBuildRuleBuilderParams params = new FakeAbstractBuildRuleBuilderParams();
    AccumulateClassNames.Builder builder = AccumulateClassNames.newAccumulateClassNamesBuilder(
        params);
    assertEquals(BuildRuleType._CLASS_NAMES, builder.getType());

    // Construct the Buildable.
    BuildTarget buildTarget = new BuildTarget("//foo/bar", "baz", "class_names");
    builder.setBuildTarget(buildTarget);
    builder.setJavaLibraryToDex(javaLibraryRule);
    BuildRule buildRule = buildRuleResolver.buildAndAddToIndex(builder);
    AccumulateClassNames accumulateClassNames = (AccumulateClassNames) buildRule.getBuildable();

    // Test the observers.
    String pathToOutput = "buck-out/gen/foo/bar/baz#class_names.classes.txt";
    assertEquals(pathToOutput,
        accumulateClassNames.getPathToOutputFile());
    assertEquals("There should not be any input files that factor into the cache key.",
        ImmutableSortedSet.of(),
        accumulateClassNames.getInputsToCompareToOutput());

    // Mock out objects so getBuildSteps() can be invoked.
    BuildContext buildContext = createMock(BuildContext.class);
    FakeBuildableContext buildableContext = new FakeBuildableContext();

    // Create the build steps.
    replayAll();
    List<Step> steps = accumulateClassNames.getBuildSteps(buildContext, buildableContext);
    verifyAll();

    // Verify the build steps.
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(new File("."));
    ExecutionContext context = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();
    Path absolutePathToOutput = projectFilesystem.resolve(Paths.get(pathToOutput));
    MoreAsserts.assertSteps("Delete old classes.txt file, if present, and then write a new one.",
        ImmutableList.of(
          "rm -f " + absolutePathToOutput,
          "mkdir -p " + absolutePathToOutput.getParent(),
          "get_class_names foo/bar/example.jar > " + pathToOutput,
          "record_abi"),
        steps,
        context);

    // Prepare to invoke the recordAbiStep by populating the accumulateClassNamesStep first.
    AccumulateClassNamesStep accumulateClassNamesStep = (AccumulateClassNamesStep) steps.get(2);
    ImmutableSortedMap<String, HashCode> classNames = ImmutableSortedMap.of();
    accumulateClassNamesStep.setClassNamesForTesting(classNames);

    // Invoke the recordAbiStep and ensure the correct data is recorded.
    Step recordAbiStep = steps.get(3);
    int exitCode = recordAbiStep.execute(context);
    String expectedAbiKey = AbiWriterProtocol.EMPTY_ABI_KEY;
    buildableContext.assertContainsMetadataMapping(AbiRule.ABI_KEY_ON_DISK_METADATA,
        expectedAbiKey);
    assertEquals(0, exitCode);

    // Populate an OnDiskBuildInfo with what should have been written by executing the rules.
    FakeOnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo();
    onDiskBuildInfo.putMetadata(AbiRule.ABI_KEY_ON_DISK_METADATA, expectedAbiKey);
    onDiskBuildInfo.setOutputFileContentsForBuildable(accumulateClassNames,
        /* lines */ ImmutableList.<String>of());

    // Hydrate the AccumulateClassNames and check its getters.
    ClassNamesAndAbiKey classNamesAndAbikey = accumulateClassNames
        .initializeFromDisk(onDiskBuildInfo);
    accumulateClassNames.setBuildOutput(classNamesAndAbikey);
    assertEquals("Should be the empty ABI key because the classNames map is empty.",
        new Sha1HashCode(expectedAbiKey),
        accumulateClassNames.getAbiKey());
    assertEquals("The classNames map available through the getter should also be empty.",
        classNames,
        accumulateClassNames.getClassNames());
  }

  @Test
  public void testInitializeFromDisk() throws IOException {
    JavaLibraryRule javaRule = new FakeJavaLibraryRule(new BuildTarget("//foo", "bar"));
    AccumulateClassNames accumulateClassNames = new AccumulateClassNames(
        new BuildTarget("//foo", "bar", "class_names"), javaRule);

    List<String> lines = ImmutableList.of(
        "com/example/Bar 087b7707a5f8e0a2adf5652e3cd2072d89a197dc",
        "com/example/Baz 62b1c2510840c0de55c13f66065a98a719be0f19",
        "com/example/Foo e4fccb7520b7795e632651323c63217c9f59f72a");
    OnDiskBuildInfo onDiskBuildInfo = new FakeOnDiskBuildInfo()
        .putMetadata(AbiRule.ABI_KEY_ON_DISK_METADATA, "f7d6d1efa11c8ceef36cc56b0ec6c3a20ddbf19f")
        .setOutputFileContentsForBuildable(accumulateClassNames, lines);

    ClassNamesAndAbiKey classNamesAndAbikey = accumulateClassNames.initializeFromDisk(
        onDiskBuildInfo);
    accumulateClassNames.setBuildOutput(classNamesAndAbikey);

    ImmutableSortedMap<String, HashCode> observedClasses = accumulateClassNames.getClassNames();
    assertEquals(
        "initializeFromDisk() should read the lines and use them to create an ImmutableSortedMap.",
        ImmutableSortedMap.of(
          "com/example/Bar", HashCode.fromString("087b7707a5f8e0a2adf5652e3cd2072d89a197dc"),
          "com/example/Baz", HashCode.fromString("62b1c2510840c0de55c13f66065a98a719be0f19"),
          "com/example/Foo", HashCode.fromString("e4fccb7520b7795e632651323c63217c9f59f72a")
        ),
        observedClasses);
    assertEquals(new Sha1HashCode("f7d6d1efa11c8ceef36cc56b0ec6c3a20ddbf19f"),
        accumulateClassNames.getAbiKey());
  }

  @Test
  public void testComputeAbiKey() {
    ImmutableSortedMap<String, HashCode> classNamesAndHashes = ImmutableSortedMap.of(
        "com/example/Foo", HashCode.fromString("e4fccb7520b7795e632651323c63217c9f59f72a"),
        "com/example/Bar", HashCode.fromString("087b7707a5f8e0a2adf5652e3cd2072d89a197dc"),
        "com/example/Baz", HashCode.fromString("62b1c2510840c0de55c13f66065a98a719be0f19")
    );
    String observedSha1 = AccumulateClassNames
        .computeAbiKey(Suppliers.ofInstance(classNamesAndHashes))
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
