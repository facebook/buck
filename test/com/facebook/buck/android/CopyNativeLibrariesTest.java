/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.toolchain.ndk.TargetCpuType;
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.File;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class CopyNativeLibrariesTest {

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() {
    this.filesystem = new FakeProjectFilesystem();
  }

  @Test
  public void testCopyNativeLibraryCommandWithoutCpuFilter() {
    Path source = filesystem.getPath("path", "to", "source");
    Path destination = filesystem.getPath("path", "to", "destination");
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        FakeBuildContext.NOOP_CONTEXT,
        ImmutableSet.of() /* cpuFilters */,
        source,
        destination,
        ImmutableList.of(
            String.format("cp -R %s%s* %s", source, File.separator, destination),
            "rename_native_executables"));
  }

  @Test
  public void testCopyNativeLibraryCommand() {
    Path source = filesystem.getPath("path", "to", "source");
    Path destination = filesystem.getPath("path", "to", "destination");
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        FakeBuildContext.NOOP_CONTEXT,
        ImmutableSet.of(TargetCpuType.ARMV7),
        source,
        destination,
        ImmutableList.of(
            String.format(
                "[ -d %s ] && mkdir -p %s && cp -R %s%s* %s",
                source.resolve("armeabi-v7a"),
                destination.resolve("armeabi-v7a"),
                source.resolve("armeabi-v7a"),
                File.separator,
                destination.resolve("armeabi-v7a")),
            "rename_native_executables"));
  }

  @Test
  public void testCopyNativeLibraryCommandWithMultipleCpuFilters() {
    Path source = filesystem.getPath("path", "to", "source");
    Path destination = filesystem.getPath("path", "to", "destination");
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        FakeBuildContext.NOOP_CONTEXT,
        ImmutableSet.of(TargetCpuType.ARM, TargetCpuType.X86),
        source,
        destination,
        ImmutableList.of(
            String.format(
                "[ -d %s ] && mkdir -p %s && cp -R %s%s* %s",
                source.resolve("armeabi"),
                destination.resolve("armeabi"),
                source.resolve("armeabi"),
                File.separator,
                destination.resolve("armeabi")),
            String.format(
                "[ -d %s ] && mkdir -p %s && cp -R %s%s* %s",
                source.resolve("x86"),
                destination.resolve("x86"),
                source.resolve("x86"),
                File.separator,
                destination.resolve("x86")),
            "rename_native_executables"));
  }

  @Test
  public void testCopyNativeLibrariesCopiesLibDirsInReverseTopoOrder() {
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    CopyNativeLibraries copyNativeLibraries =
        new CopyNativeLibraries(
            target,
            new FakeProjectFilesystem(),
            ruleFinder,
            ImmutableSet.of(),
            ImmutableSet.of(),
            ImmutableSet.of(FakeSourcePath.of("lib1"), FakeSourcePath.of("lib2")),
            ImmutableSet.of(),
            ImmutableSet.of(),
            "dex");

    ImmutableList<Step> steps =
        copyNativeLibraries.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(pathResolver), new FakeBuildableContext());

    Iterable<String> descriptions =
        Iterables.transform(steps, step -> step.getDescription(TestExecutionContext.newInstance()));
    assertThat(
        "lib1 contents should be copied *after* lib2",
        Iterables.indexOf(descriptions, Predicates.containsPattern("lib1")),
        Matchers.greaterThan(Iterables.indexOf(descriptions, Predicates.containsPattern("lib2"))));
  }

  private void createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
      BuildContext context,
      ImmutableSet<TargetCpuType> cpuFilters,
      Path sourceDir,
      Path destinationDir,
      ImmutableList<String> expectedCommandDescriptions) {
    // Invoke copyNativeLibrary to populate the steps.
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
    CopyNativeLibraries.copyNativeLibrary(
        context, filesystem, sourceDir, destinationDir, cpuFilters, stepsBuilder);
    ImmutableList<Step> steps = stepsBuilder.build();

    assertEquals(steps.size(), expectedCommandDescriptions.size());
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setCellPathResolver(TestCellPathResolver.get(filesystem))
            .build();

    for (int i = 0; i < steps.size(); ++i) {
      String description = steps.get(i).getDescription(executionContext);
      assertEquals(expectedCommandDescriptions.get(i), description);
    }
  }
}
