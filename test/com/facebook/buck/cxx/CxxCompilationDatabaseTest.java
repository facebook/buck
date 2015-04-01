/*
 * Copyright 2015-present Facebook, Inc.
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
package com.facebook.buck.cxx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cxx.CxxCompilationDatabase.JsonSerializableDatabaseEntry;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class CxxCompilationDatabaseTest {

  @Test
  public void testCompilationDatabseWithCombinedPreprocessAndCompileStrategy() {
    BuildTarget testBuildTarget = BuildTarget
        .builder(BuildTargetFactory.newInstance("//foo:baz"))
        .addAllFlavors(
            ImmutableSet.of(CxxCompilationDatabase.COMPILATION_DATABASE))
        .build();
    BuildRuleParams testBuildRuleParams = new FakeBuildRuleParamsBuilder(testBuildTarget)
        .setTargetGraph(
            TargetGraphFactory.newInstance(
                new CxxLibraryBuilder(testBuildTarget).build()))
        .build();

    BuildRuleResolver testBuildRuleResolver = new BuildRuleResolver();
    SourcePathResolver testSourcePathResolver = new SourcePathResolver(testBuildRuleResolver);

    BuildTarget compileTarget = BuildTarget
        .builder(testBuildRuleParams.getBuildTarget().getUnflavoredBuildTarget())
        .addFlavors(
            ImmutableFlavor.of("compile-test.cpp"))
        .build();
    BuildRuleParams compileBuildRuleParams = new FakeBuildRuleParamsBuilder(compileTarget)
        .build();
    CxxPreprocessAndCompile testCompileRule = new CxxPreprocessAndCompile(
        compileBuildRuleParams,
        testSourcePathResolver,
        new HashedFileTool(Paths.get("compiler")),
        CxxPreprocessAndCompileStep.Operation.COMPILE,
        ImmutableList.<String>of(),
        Paths.get("test.o"),
        new TestSourcePath("test.cpp"),
        ImmutableList.of(
            Paths.get("foo/bar"),
            Paths.get("test")),
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of(),
        ImmutableCxxHeaders.builder().build(),
        Optional.<DebugPathSanitizer>absent());

    testBuildRuleResolver.addToIndex(testCompileRule);

    CxxCompilationDatabase compilationDatabase = CxxCompilationDatabase.createCompilationDatabase(
        testBuildRuleParams,
        testBuildRuleResolver,
        testSourcePathResolver,
        CxxSourceRuleFactory.Strategy.COMBINED_PREPROCESS_AND_COMPILE);

    assertEquals(
        "getPathToOutputFile() should be a function of the build target.",
        Paths.get("buck-out/gen/foo/__baz#compilation-database.json"),
        compilationDatabase.getPathToOutputFile());

    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    BuildableContext buildableContext = new FakeBuildableContext();
    List<Step> buildSteps = compilationDatabase.getBuildSteps(buildContext, buildableContext);
    assertEquals(2, buildSteps.size());
    assertTrue(buildSteps.get(0) instanceof MkdirStep);
    assertTrue(buildSteps.get(1) instanceof
            CxxCompilationDatabase.GenerateCompilationCommandsJson);

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
    CxxCompilationDatabase.GenerateCompilationCommandsJson step =
        (CxxCompilationDatabase.GenerateCompilationCommandsJson) buildSteps.get(1);
    Iterable<JsonSerializableDatabaseEntry> observedEntries =
        step.createEntries(context);
    Iterable<JsonSerializableDatabaseEntry> expectedEntries =
        ImmutableList.of(
          new JsonSerializableDatabaseEntry(
              root + "/foo",
              root + "/test.cpp",
              Joiner.on(' ').join(
                  "compiler",
                  "-c",
                  "-I",
                  "foo/bar",
                  "-I",
                  "test",
                  "-o",
                  "test.o",
                  "test.cpp")));
    MoreAsserts.assertIterablesEquals(observedEntries, expectedEntries);
  }

  @Test
  public void testCompilationDatabseWithSeperatedPreprocessAndCompileStrategy() {
    BuildTarget testBuildTarget = BuildTarget
        .builder(BuildTargetFactory.newInstance("//foo:baz"))
        .addAllFlavors(
            ImmutableSet.of(CxxCompilationDatabase.COMPILATION_DATABASE))
        .build();
    BuildRuleParams testBuildRuleParams = new FakeBuildRuleParamsBuilder(testBuildTarget)
        .setTargetGraph(
            TargetGraphFactory.newInstance(
                new CxxLibraryBuilder(testBuildTarget).build()))
        .build();

    BuildRuleResolver testBuildRuleResolver = new BuildRuleResolver();
    SourcePathResolver testSourcePathResolver = new SourcePathResolver(testBuildRuleResolver);

    BuildTarget preprocessTarget = BuildTarget
        .builder(testBuildRuleParams.getBuildTarget().getUnflavoredBuildTarget())
        .addFlavors(
            ImmutableFlavor.of("preprocess-test.cpp"))
        .build();
    BuildRuleParams preprocessBuildRuleParams = new FakeBuildRuleParamsBuilder(preprocessTarget)
        .build();
    CxxPreprocessAndCompile testPreprocessRule = new CxxPreprocessAndCompile(
        preprocessBuildRuleParams,
        testSourcePathResolver,
        new HashedFileTool(Paths.get("compiler")),
        CxxPreprocessAndCompileStep.Operation.PREPROCESS,
        ImmutableList.<String>of(),
        Paths.get("test.ii"),
        new TestSourcePath("test.cpp"),
        ImmutableList.of(
            Paths.get("foo/bar"),
            Paths.get("test")),
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of(),
        ImmutableCxxHeaders.builder().build(),
        Optional.<DebugPathSanitizer>absent());

    BuildTarget compileTarget = BuildTarget
        .builder(testBuildRuleParams.getBuildTarget().getUnflavoredBuildTarget())
        .addFlavors(
            ImmutableFlavor.of("compile-test.cpp"))
        .build();
    BuildRuleParams compileBuildRuleParams = new FakeBuildRuleParamsBuilder(compileTarget)
        .setDeps(ImmutableSortedSet.<BuildRule>of(testPreprocessRule))
        .build();
    CxxPreprocessAndCompile testCompileRule = new CxxPreprocessAndCompile(
        compileBuildRuleParams,
        testSourcePathResolver,
        new HashedFileTool(Paths.get("compiler")),
        CxxPreprocessAndCompileStep.Operation.COMPILE,
        ImmutableList.<String>of(),
        Paths.get("test.o"),
        new TestSourcePath("test.ii"),
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of(),
        ImmutableList.<Path>of(),
        ImmutableCxxHeaders.builder().build(),
        Optional.<DebugPathSanitizer>absent());

    testBuildRuleResolver.addToIndex(testPreprocessRule);
    testBuildRuleResolver.addToIndex(testCompileRule);

    CxxCompilationDatabase compilationDatabase = CxxCompilationDatabase.createCompilationDatabase(
        testBuildRuleParams,
        testBuildRuleResolver,
        testSourcePathResolver,
        CxxSourceRuleFactory.Strategy.SEPARATE_PREPROCESS_AND_COMPILE);

    assertEquals(
        "getPathToOutputFile() should be a function of the build target.",
        Paths.get("buck-out/gen/foo/__baz#compilation-database.json"),
        compilationDatabase.getPathToOutputFile());

    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    BuildableContext buildableContext = new FakeBuildableContext();
    List<Step> buildSteps = compilationDatabase.getBuildSteps(buildContext, buildableContext);
    assertEquals(2, buildSteps.size());
    assertTrue(buildSteps.get(0) instanceof MkdirStep);
    assertTrue(buildSteps.get(1) instanceof
            CxxCompilationDatabase.GenerateCompilationCommandsJson);

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
    CxxCompilationDatabase.GenerateCompilationCommandsJson step =
        (CxxCompilationDatabase.GenerateCompilationCommandsJson) buildSteps.get(1);
    Iterable<JsonSerializableDatabaseEntry> observedEntries =
        step.createEntries(context);
    Iterable<JsonSerializableDatabaseEntry> expectedEntries =
        ImmutableList.of(
            new JsonSerializableDatabaseEntry(
                root + "/foo",
                root + "/test.cpp",
                Joiner.on(' ').join(
                    "compiler",
                    "-c",
                    "-I",
                    "foo/bar",
                    "-I",
                    "test",
                    "-o",
                    "test.o",
                    "test.cpp")));
    MoreAsserts.assertIterablesEquals(expectedEntries, observedEntries);
  }
}
