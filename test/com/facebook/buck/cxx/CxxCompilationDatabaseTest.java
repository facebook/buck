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
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.DependencyAggregation;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.GccCompiler;
import com.facebook.buck.cxx.toolchain.GccPreprocessor;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.args.AddsToRuleKeyFunction;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.Test;

public class CxxCompilationDatabaseTest {

  @Test
  public void testCompilationDatabase() {
    BuildTarget testBuildTarget =
        BuildTargetFactory.newInstance("//foo:baz")
            .withAppendedFlavors(ImmutableSet.of(CxxCompilationDatabase.COMPILATION_DATABASE));

    String root = "/Users/user/src";
    Path fakeRoot = Paths.get(root);
    ProjectFilesystem filesystem = new FakeProjectFilesystem(fakeRoot);

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver testSourcePathResolver = DefaultSourcePathResolver.from(ruleFinder);

    HeaderSymlinkTree privateSymlinkTree =
        CxxDescriptionEnhancer.createHeaderSymlinkTree(
            testBuildTarget,
            filesystem,
            ruleFinder,
            graphBuilder,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableMap.of(),
            HeaderVisibility.PRIVATE,
            true);
    graphBuilder.addToIndex(privateSymlinkTree);
    HeaderSymlinkTree exportedSymlinkTree =
        CxxDescriptionEnhancer.createHeaderSymlinkTree(
            testBuildTarget,
            filesystem,
            ruleFinder,
            graphBuilder,
            CxxPlatformUtils.DEFAULT_PLATFORM,
            ImmutableMap.of(),
            HeaderVisibility.PUBLIC,
            true);
    graphBuilder.addToIndex(exportedSymlinkTree);

    BuildTarget compileTarget = testBuildTarget.withFlavors(InternalFlavor.of("compile-test.cpp"));

    PreprocessorFlags preprocessorFlags =
        PreprocessorFlags.builder()
            .addIncludes(
                CxxHeadersDir.of(
                    CxxPreprocessables.IncludeType.SYSTEM, FakeSourcePath.of("/foo/bar")),
                CxxHeadersDir.of(CxxPreprocessables.IncludeType.SYSTEM, FakeSourcePath.of("/test")))
            .build();

    ImmutableSortedSet.Builder<CxxPreprocessAndCompile> rules = ImmutableSortedSet.naturalOrder();
    class FrameworkPathFunction implements AddsToRuleKeyFunction<FrameworkPath, Path> {
      @Override
      public Path apply(FrameworkPath input) {
        throw new UnsupportedOperationException("should not be called");
      }
    }
    BuildTarget aggregatedDeps = compileTarget.withFlavors(InternalFlavor.of("deps"));
    rules.add(
        graphBuilder.addToIndex(
            CxxPreprocessAndCompile.preprocessAndCompile(
                compileTarget,
                filesystem,
                ruleFinder,
                new PreprocessorDelegate(
                    CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                    FakeSourcePath.of(filesystem.getRootPath()),
                    new GccPreprocessor(
                        new HashedFileTool(
                            PathSourcePath.of(filesystem, Paths.get("preprocessor")))),
                    preprocessorFlags,
                    new FrameworkPathFunction(),
                    /* leadingIncludePaths */ Optional.empty(),
                    Optional.of(
                        new DependencyAggregation(
                            aggregatedDeps,
                            filesystem,
                            ImmutableSortedSet.of(privateSymlinkTree, exportedSymlinkTree))),
                    ImmutableSortedSet.of()),
                new CompilerDelegate(
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    new GccCompiler(
                        new HashedFileTool(PathSourcePath.of(filesystem, Paths.get("compiler"))),
                        false),
                    CxxToolFlags.of(),
                    Optional.empty()),
                "test.o",
                FakeSourcePath.of(filesystem, "test.cpp"),
                CxxSource.Type.CXX,
                Optional.empty(),
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER)));

    CxxCompilationDatabase compilationDatabase =
        CxxCompilationDatabase.createCompilationDatabase(
            testBuildTarget, filesystem, rules.build());
    graphBuilder.addToIndex(compilationDatabase);

    assertThat(
        compilationDatabase.getRuntimeDeps(ruleFinder).collect(ImmutableSet.toImmutableSet()),
        Matchers.contains(aggregatedDeps));

    assertEquals(
        "getPathToOutput() should be a function of the build target.",
        BuildTargetPaths.getGenPath(filesystem, testBuildTarget, "__%s/compile_commands.json"),
        testSourcePathResolver.getRelativePath(compilationDatabase.getSourcePathToOutput()));

    List<Step> buildSteps =
        compilationDatabase.getBuildSteps(
            FakeBuildContext.withSourcePathResolver(testSourcePathResolver),
            new FakeBuildableContext());
    assertEquals(2, buildSteps.size());
    assertTrue(buildSteps.get(0) instanceof MkdirStep);
    assertTrue(buildSteps.get(1) instanceof CxxCompilationDatabase.GenerateCompilationCommandsJson);

    CxxCompilationDatabase.GenerateCompilationCommandsJson step =
        (CxxCompilationDatabase.GenerateCompilationCommandsJson) buildSteps.get(1);
    Iterable<CxxCompilationDatabaseEntry> observedEntries =
        step.createEntries().collect(Collectors.toList());
    Iterable<CxxCompilationDatabaseEntry> expectedEntries =
        ImmutableList.of(
            CxxCompilationDatabaseEntry.of(
                root,
                root + "/test.cpp",
                ImmutableList.of(
                    "/Users/user/src/compiler",
                    "-x",
                    "c++",
                    "-isystem",
                    "/foo/bar",
                    "-isystem",
                    "/test",
                    "-o",
                    "buck-out/gen/foo/baz#compile-test.cpp/test.o",
                    "-c",
                    "-MD",
                    "-MF",
                    "buck-out/gen/foo/baz#compile-test.cpp/test.o.dep",
                    "test.cpp")));
    MoreAsserts.assertIterablesEquals(expectedEntries, observedEntries);
  }
}
