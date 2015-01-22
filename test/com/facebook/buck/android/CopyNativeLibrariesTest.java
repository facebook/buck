/*
 * Copyright 2014-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.android.AndroidBinary.TargetCpuType;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.google.common.base.Function;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.nio.file.Paths;

public class CopyNativeLibrariesTest {

  @Test
  public void testCopyNativeLibraryCommandWithoutCpuFilter() {
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        ImmutableSet.<TargetCpuType>of() /* cpuFilters */,
        "/path/to/source",
        "/path/to/destination/",
        ImmutableList.of(
            "cp -R /path/to/source/* /path/to/destination",
            "rename_native_executables"));
  }

  @Test
  public void testCopyNativeLibraryCommand() {
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        ImmutableSet.of(TargetCpuType.ARMV7),
        "/path/to/source",
        "/path/to/destination/",
        ImmutableList.of(
            "[ -d /path/to/source/armeabi-v7a ] && mkdir -p /path/to/destination/armeabi-v7a " +
                "&& cp -R /path/to/source/armeabi-v7a/* /path/to/destination/armeabi-v7a",
            "rename_native_executables"));
  }

  @Test
  public void testCopyNativeLibraryCommandWithMultipleCpuFilters() {
    createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
        ImmutableSet.of(TargetCpuType.ARM, TargetCpuType.X86),
        "/path/to/source",
        "/path/to/destination/",
        ImmutableList.of(
            "[ -d /path/to/source/armeabi ] && mkdir -p /path/to/destination/armeabi " +
                "&& cp -R /path/to/source/armeabi/* /path/to/destination/armeabi",
            "[ -d /path/to/source/x86 ] && mkdir -p /path/to/destination/x86 " +
                "&& cp -R /path/to/source/x86/* /path/to/destination/x86",
            "rename_native_executables"));
  }

  @Test
  public void testCopyNativeLibrariesCopiesLibDirsInReverseTopoOrder() {
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    CopyNativeLibraries copyNativeLibraries =
        new CopyNativeLibraries(
            BuildRuleParamsFactory.createTrivialBuildRuleParams(target),
            new SourcePathResolver(new BuildRuleResolver()),
            ImmutableSet.of(Paths.get("lib1"), Paths.get("lib2")),
            ImmutableSet.<TargetCpuType>of(),
            ImmutableMap.<TargetCpuType, NdkCxxPlatform>of(),
            ImmutableMap.<Pair<TargetCpuType, String>, SourcePath>of());

    ImmutableList<Step> steps =
        copyNativeLibraries.getBuildSteps(
            FakeBuildContext.NOOP_CONTEXT,
            new FakeBuildableContext());

    Iterable<String> descriptions =
        Iterables.transform(
            steps,
            new Function<Step, String>() {
              @Override
              public String apply(Step step) {
                return step.getDescription(TestExecutionContext.newInstance());
              }
            });
    assertThat(
        "lib1 contents should be copied *after* lib2",
        Iterables.indexOf(
            descriptions,
            Predicates.containsPattern("lib1")),
        Matchers.greaterThan(
            Iterables.indexOf(
                descriptions,
                Predicates.containsPattern("lib2"))));
  }

  private void createAndroidBinaryRuleAndTestCopyNativeLibraryCommand(
      ImmutableSet<TargetCpuType> cpuFilters,
      String sourceDir,
      String destinationDir,
      ImmutableList<String> expectedCommandDescriptions) {
    // Invoke copyNativeLibrary to populate the steps.
    ImmutableList.Builder<Step> stepsBuilder = ImmutableList.builder();
    CopyNativeLibraries.copyNativeLibrary(
        Paths.get(sourceDir), Paths.get(destinationDir), cpuFilters, stepsBuilder);
    ImmutableList<Step> steps = stepsBuilder.build();

    assertEquals(steps.size(), expectedCommandDescriptions.size());
    ExecutionContext context = TestExecutionContext.newInstance();

    for (int i = 0; i < steps.size(); ++i) {
      String description = steps.get(i).getDescription(context);
      assertEquals(expectedCommandDescriptions.get(i), description);
    }
  }
}
