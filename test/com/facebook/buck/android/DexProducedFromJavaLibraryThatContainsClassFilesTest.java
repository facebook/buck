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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.AccumulateClassNames;
import com.facebook.buck.java.JavaLibraryRule;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.hash.HashCode;

import org.easymock.EasyMockSupport;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;

public class DexProducedFromJavaLibraryThatContainsClassFilesTest extends EasyMockSupport {

  @Test
  public void testGetBuildStepsWhenThereAreClassesToDex() throws IOException {
    JavaLibraryRule javaLibraryRule = createMock(JavaLibraryRule.class);
    expect(javaLibraryRule.getPathToOutputFile()).andReturn("buck-out/gen/foo/bar.jar");

    AccumulateClassNames accumulateClassNames = createMock(AccumulateClassNames.class);
    expect(accumulateClassNames.getClassNames()).andReturn(
        ImmutableSortedMap.of("com/example/Foo", HashCode.fromString("cafebabe")));
    expect(accumulateClassNames.getJavaLibraryRule()).andReturn(javaLibraryRule);

    BuildContext context = createMock(BuildContext.class);
    BuildableContext buildableContext = createMock(BuildableContext.class);

    replayAll();

    BuildTarget buildTarget = new BuildTarget("//foo", "bar");
    DexProducedFromJavaLibraryThatContainsClassFiles preDex =
        new DexProducedFromJavaLibraryThatContainsClassFiles(buildTarget, accumulateClassNames);
    List<Step> steps = preDex.getBuildSteps(context, buildableContext);

    verifyAll();
    resetAll();

    AndroidPlatformTarget androidPlatformTarget = createMock(AndroidPlatformTarget.class);
    expect(androidPlatformTarget.getDxExecutable()).andReturn(new File("/usr/bin/dx"));

    ProjectFilesystem projectFilesystem = createMock(ProjectFilesystem.class);
    expect(projectFilesystem.resolve(Paths.get("buck-out/gen/foo")))
        .andReturn(Paths.get("/home/user/buck-out/gen/foo"));
    expect(projectFilesystem.resolve(Paths.get("buck-out/gen/foo/bar.dex.jar")))
        .andReturn(Paths.get("/home/user/buck-out/gen/foo/bar.dex.jar"));
    expect(projectFilesystem.resolve(Paths.get("buck-out/gen/foo/bar.jar")))
        .andReturn(Paths.get("/home/user/buck-out/gen/foo/bar.jar"));
    replayAll();

    ExecutionContext executionContext = TestExecutionContext
        .newBuilder()
        .setAndroidPlatformTarget(Optional.of(androidPlatformTarget))
        .setProjectFilesystem(projectFilesystem)
        .build();

    String expectedDxCommand = "/usr/bin/dx" +
        " --dex --no-optimize --force-jumbo --output buck-out/gen/foo/bar.dex.jar " +
        "/home/user/buck-out/gen/foo/bar.jar";
    MoreAsserts.assertSteps("Generate bar.dex.jar.",
        ImmutableList.of(
          "rm -f /home/user/buck-out/gen/foo/bar.dex.jar",
          "mkdir -p /home/user/buck-out/gen/foo",
          expectedDxCommand,
          "record_dx_success"),
        steps,
        executionContext);

    verifyAll();
  }

  @Test
  public void testGetBuildStepsWhenThereAreNoClassesToDex() throws IOException {
    AccumulateClassNames accumulateClassNames = createMock(AccumulateClassNames.class);
    expect(accumulateClassNames.getClassNames()).andReturn(
        ImmutableSortedMap.<String, HashCode>of());

    BuildContext context = createMock(BuildContext.class);
    BuildableContext buildableContext = createMock(BuildableContext.class);

    replayAll();

    BuildTarget buildTarget = new BuildTarget("//foo", "bar");
    DexProducedFromJavaLibraryThatContainsClassFiles preDex =
        new DexProducedFromJavaLibraryThatContainsClassFiles(buildTarget, accumulateClassNames);
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
          "mkdir -p /home/user/buck-out/gen/foo"),
        steps,
        executionContext);

    verifyAll();
  }

  @Test
  public void testObserverMethods() {
    AccumulateClassNames accumulateClassNames = createMock(AccumulateClassNames.class);
    expect(accumulateClassNames.getClassNames())
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
}
