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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.CompilationDatabase.GenerateCompilationCommandsJson;
import com.facebook.buck.apple.CompilationDatabase.JsonSerializableDatabaseEntry;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.model.Pair;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;

public class CompilationDatabaseTest {

  // These will be initialized by setUpTestValues().
  private BuildRuleResolver testBuildRuleResolver;
  private SourcePathResolver testSourcePathResolver;
  private TargetSources testTargetSources;
  private BuildTarget testBuildTarget;
  private AppleConfig appleConfig;

  @Test
  public void testGetPathToOutputFile() {
    setUpTestValues();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:baz");
    CompilationDatabase compilationDatabase = new CompilationDatabase(
        new FakeBuildRuleParamsBuilder(buildTarget).build(),
        testSourcePathResolver,
        appleConfig,
        testTargetSources,
        /* frameworks */ ImmutableSortedSet.<String>of(),
        /* includePaths */ ImmutableSet.<Path>of(),
        /* pchFile */ Optional.<SourcePath>absent());

    createTestCompilationDatabase();
    assertEquals(
        "getPathToOutputFile() should be a function of the build target.",
        Paths.get("buck-out/gen/foo/__baz_compilation_database.json"),
        compilationDatabase.getPathToOutputFile());
  }

  @Test
  public void testGetInputsToCompareToOutput() {
    setUpTestValues();

    Pair<SourcePath, String> publicHeader = new Pair<SourcePath, String>(
        new TestSourcePath("Foo/Hello.h"),
        "public");
    Collection<AppleSource> appleSources = ImmutableList.of(
        AppleSource.ofSourcePathWithFlags(publicHeader),
        AppleSource.ofSourcePath(new TestSourcePath("Foo/Hello.m")));
    TargetSources targetSources = TargetSources.ofAppleSources(
        testSourcePathResolver,
        appleSources);

    CompilationDatabase compilationDatabase = new CompilationDatabase(
        new FakeBuildRuleParamsBuilder(testBuildTarget).build(),
        testSourcePathResolver,
        appleConfig,
        targetSources,
        /* frameworks */ ImmutableSortedSet.<String>of(),
        /* includePaths */ ImmutableSet.<Path>of(),
        /* pchFile */ Optional.<SourcePath>absent());

    MoreAsserts.assertIterablesEquals(
        "getInputsToCompareToOutput() should contain files in targetSources.",
        ImmutableList.of(
            Paths.get("Foo/Hello.h"),
            Paths.get("Foo/Hello.m")),
        compilationDatabase.getInputsToCompareToOutput());
  }

  @Test
  public void testGetBuildSteps() {
    CompilationDatabase compilationDatabase = createTestCompilationDatabase();
    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    BuildableContext buildableContext = new FakeBuildableContext();
    List<Step> buildSteps = compilationDatabase.getBuildSteps(buildContext, buildableContext);
    assertEquals(4, buildSteps.size());
    int stepIndex = 0;
    assertTrue(buildSteps.get(stepIndex++) instanceof MkdirStep);
    assertTrue(buildSteps.get(stepIndex++) instanceof AbstractExecutionStep);
    assertTrue(buildSteps.get(stepIndex++) instanceof MkdirStep);
    assertTrue(buildSteps.get(stepIndex++) instanceof GenerateCompilationCommandsJson);
  }

  @Test
  public void testGenerateCompilationCommandsStep() {
    CompilationDatabase compilationDatabase = createTestCompilationDatabase();
    List<Step> buildSteps = compilationDatabase.getBuildSteps(
        FakeBuildContext.NOOP_CONTEXT,
        new FakeBuildableContext());
    Step step = buildSteps.get(3);
    assertTrue(step instanceof GenerateCompilationCommandsJson);
    GenerateCompilationCommandsJson generateCompilationCommandsStep =
        (GenerateCompilationCommandsJson) step;

    final String root = "/Users/user/src";
    final Path fakeRoot = Paths.get(root);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem() {
      @Override
      public Path resolve(Path relativePath) {
        return fakeRoot.resolve(relativePath);
      }
    };
    ExecutionContext context = TestExecutionContext
        .newBuilder()
        .setProjectFilesystem(projectFilesystem)
        .build();
    Iterable<JsonSerializableDatabaseEntry> expectedEntries = ImmutableList.of(
        new JsonSerializableDatabaseEntry(
            root + "/foo",
            root + "/foo/Hello.m",
            Joiner.on(' ').join(
                "clang",
                "-x",
                "objective-c",
                "-arch",
                "i386",
                "-mios-simulator-version-min=7.0",
                "-fmessage-length=0",
                "-fdiagnostics-show-note-include-stack",
                "-fmacro-backtrace-limit=0",
                "-std=gnu99",
                "-fpascal-strings",
                "-fexceptions",
                "-fasm-blocks",
                "-fstrict-aliasing",
                "-fobjc-abi-version=2",
                "-fobjc-legacy-dispatch",
                "-O0",
                "-g",
                "-MMD",
                "-fobjc-arc",
                "-isysroot",
                "/path/to/somewhere" +
                    "/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk",
                "-F/path/to/somewhere/" +
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk/" +
                    "System/Library/Frameworks/CoreLocation.framework",
                "-F/path/to/somewhere/" +
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk/" +
                    "System/Library/Frameworks/Foundation.framework",
                "-F/path/to/somewhere/" +
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk/" +
                    "System/Library/Frameworks/UIKit.framework",
                "-I/Users/user/src/buck-out/gen/library/lib.hmap",
                "-include",
                "/Users/user/src/foo/bar.pch",
                "-c",
                root + "/foo/Hello.m")),
        new JsonSerializableDatabaseEntry(
            root + "/foo",
            root + "/foo/Hello.h",
            Joiner.on(' ').join(
                "clang",
                "-x",
                "objective-c",
                "-arch",
                "i386",
                "-mios-simulator-version-min=7.0",
                "-fmessage-length=0",
                "-fdiagnostics-show-note-include-stack",
                "-fmacro-backtrace-limit=0",
                "-std=gnu99",
                "-fpascal-strings",
                "-fexceptions",
                "-fasm-blocks",
                "-fstrict-aliasing",
                "-fobjc-abi-version=2",
                "-fobjc-legacy-dispatch",
                "-O0",
                "-g",
                "-MMD",
                "-fobjc-arc",
                "-isysroot",
                "/path/to/somewhere" +
                    "/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk",
                "-F/path/to/somewhere/" +
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk/" +
                    "System/Library/Frameworks/CoreLocation.framework",
                "-F/path/to/somewhere/" +
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk/" +
                    "System/Library/Frameworks/Foundation.framework",
                "-F/path/to/somewhere/" +
                    "Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk/" +
                    "System/Library/Frameworks/UIKit.framework",
                "-I/Users/user/src/buck-out/gen/library/lib.hmap",
                "-include",
                "/Users/user/src/foo/bar.pch",
                "-c",
                root + "/foo/Hello.h")));
    Iterable<JsonSerializableDatabaseEntry> observedEntries = generateCompilationCommandsStep
        .createEntries(context);
    MoreAsserts.assertIterablesEquals(expectedEntries, observedEntries);
  }

  private void setUpTestValues() {
    testBuildRuleResolver = new BuildRuleResolver();
    testSourcePathResolver = new SourcePathResolver(testBuildRuleResolver);
    Pair<SourcePath, String> publicHeader = new Pair<SourcePath, String>(
        new TestSourcePath("foo/Hello.h"),
        "public"); // Note that "public" should not be included in the clang flags.
    Collection<AppleSource> appleSources = ImmutableList.of(
        AppleSource.ofSourcePathWithFlags(publicHeader),
        AppleSource.ofSourcePath(new TestSourcePath("foo/Hello.m")));
    testTargetSources = TargetSources.ofAppleSources(testSourcePathResolver, appleSources);
    testBuildTarget = BuildTargetFactory.newInstance("//foo:bar");
  }

  private CompilationDatabase createTestCompilationDatabase() {
    setUpTestValues();

    ImmutableSortedSet<String> frameworks = ImmutableSortedSet.of(
        "$SDKROOT/System/Library/Frameworks/CoreLocation.framework",
        "$SDKROOT/System/Library/Frameworks/Foundation.framework",
        "$SDKROOT/System/Library/Frameworks/UIKit.framework");
    ImmutableSet<Path> includePaths = ImmutableSet.of(
        Paths.get("/Users/user/src/buck-out/gen/library/lib.hmap"));
    Optional<SourcePath> pchFile = Optional.<SourcePath>of(new PathSourcePath(Paths.get(
        "foo/bar.pch")));
    ImmutableMap<AppleSdk, AppleSdkPaths> appleSdkPaths = ImmutableMap.of(
          (AppleSdk) ImmutableAppleSdk.builder()
              .setName("iphonesimulator8.0")
              .setVersion("8.0")
              .setApplePlatform(ApplePlatform.IPHONESIMULATOR)
              .addArchitectures("i386", "x86_64")
              .build(),
          (AppleSdkPaths) ImmutableAppleSdkPaths.builder()
              .setDeveloperPath(Paths.get("developerPath"))
              .addToolchainPaths(Paths.get("toolchainPath"))
              .setPlatformDeveloperPath(Paths.get("platformDeveloperPath"))
              .setSdkPath(Paths.get("/path/to/somewhere" +
                  "/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator8.0.sdk"))
              .build());
    appleConfig = new FakeAppleConfig().setAppleSdkPaths(appleSdkPaths);
    return new CompilationDatabase(
        new FakeBuildRuleParamsBuilder(testBuildTarget).build(),
        testSourcePathResolver,
        appleConfig,
        testTargetSources,
        frameworks,
        includePaths,
        pchFile);
  }
}
