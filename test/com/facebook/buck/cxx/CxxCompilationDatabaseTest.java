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

import com.facebook.buck.cli.BuildTargetNodeToBuildRuleTransformer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeBuildableContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class CxxCompilationDatabaseTest {

  private void runCombinedTest(
      CxxPreprocessMode strategy,
      ImmutableList<String> expectedArguments) {
    BuildTarget testBuildTarget = BuildTarget
        .builder(BuildTargetFactory.newInstance("//foo:baz"))
        .addAllFlavors(
            ImmutableSet.of(CxxCompilationDatabase.COMPILATION_DATABASE))
        .build();

    final String root = "/Users/user/src";
    final Path fakeRoot = Paths.get(root);
    ProjectFilesystem filesystem = new FakeProjectFilesystem() {
      @Override
      public Path getRootPath() {
        return fakeRoot;
      }

      @Override
      public Path resolve(Path relativePath) {
        return fakeRoot.resolve(relativePath);
      }
    };

    BuildRuleParams testBuildRuleParams = new FakeBuildRuleParamsBuilder(testBuildTarget)
        .setProjectFilesystem(filesystem)
        .build();

    BuildRuleResolver testBuildRuleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new BuildTargetNodeToBuildRuleTransformer());
    SourcePathResolver testSourcePathResolver = new SourcePathResolver(testBuildRuleResolver);

    BuildTarget preprocessTarget = BuildTarget
        .builder(testBuildRuleParams.getBuildTarget().getUnflavoredBuildTarget())
        .addFlavors(
            ImmutableFlavor.of("preprocess-test.cpp"))
        .build();
    BuildTarget compileTarget = BuildTarget
        .builder(testBuildRuleParams.getBuildTarget().getUnflavoredBuildTarget())
        .addFlavors(
            ImmutableFlavor.of("compile-test.cpp"))
        .build();

    PreprocessorFlags preprocessorFlags = PreprocessorFlags.builder()
        .addIncludePaths(
            filesystem.resolve("foo/bar"),
            filesystem.resolve("test"))
        .build();

    ImmutableSortedSet.Builder<CxxPreprocessAndCompile> rules = ImmutableSortedSet.naturalOrder();
    BuildRuleParams compileBuildRuleParams;
    switch (strategy) {
      case SEPARATE:
        CxxPreprocessAndCompile preprocessRule =
            CxxPreprocessAndCompile.preprocess(
                new FakeBuildRuleParamsBuilder(preprocessTarget)
                    .setProjectFilesystem(filesystem)
                    .build(),
                testSourcePathResolver,
                new PreprocessorDelegate(
                    testSourcePathResolver,
                    CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER,
                    filesystem.getRootPath(),
                    new DefaultPreprocessor(new HashedFileTool(Paths.get("compiler"))),
                    preprocessorFlags,
                    new RuleKeyAppendableFunction<FrameworkPath, Path>() {
                      @Override
                      public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
                        return builder;
                      }

                      @Override
                      public Path apply(FrameworkPath input) {
                        throw new UnsupportedOperationException("should not be called");
                      }
                    },
                    ImmutableList.<CxxHeaders>of()),
                new CompilerDelegate(
                    testSourcePathResolver,
                    CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER,
                    new DefaultCompiler(new HashedFileTool(Paths.get("compiler"))),
                    CxxToolFlags.of()),
                Paths.get("test.ii"),
                new FakeSourcePath(filesystem, "test.cpp"),
                CxxSource.Type.CXX,
                CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER);
        rules.add(preprocessRule);
        compileBuildRuleParams = new FakeBuildRuleParamsBuilder(compileTarget)
            .setProjectFilesystem(filesystem)
            .setDeclaredDeps(ImmutableSortedSet.<BuildRule>of(preprocessRule))
            .build();
        rules.add(
            CxxPreprocessAndCompile.compile(
                compileBuildRuleParams,
                testSourcePathResolver,
                new CompilerDelegate(
                    testSourcePathResolver,
                    CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER,
                    new DefaultCompiler(new HashedFileTool(Paths.get("compiler"))),
                    CxxToolFlags.of()),
                Paths.get("test.o"),
                new FakeSourcePath(filesystem, "test.ii"),
                CxxSource.Type.CXX_CPP_OUTPUT,
                CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER));
        break;
      case COMBINED:
      case PIPED:
        compileBuildRuleParams = new FakeBuildRuleParamsBuilder(compileTarget)
            .setProjectFilesystem(filesystem)
            .build();
        rules.add(
            CxxPreprocessAndCompile.preprocessAndCompile(
                compileBuildRuleParams,
                testSourcePathResolver,
                new PreprocessorDelegate(
                    testSourcePathResolver,
                    CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER,
                    filesystem.getRootPath(),
                    new DefaultPreprocessor(new HashedFileTool(Paths.get("preprocessor"))),
                    preprocessorFlags,
                    new RuleKeyAppendableFunction<FrameworkPath, Path>() {
                      @Override
                      public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
                        return builder;
                      }

                      @Override
                      public Path apply(FrameworkPath input) {
                        throw new UnsupportedOperationException("should not be called");
                      }
                    },
                    ImmutableList.<CxxHeaders>of()),
                new CompilerDelegate(
                    testSourcePathResolver,
                    CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER,
                    new DefaultCompiler(new HashedFileTool(Paths.get("compiler"))),
                    CxxToolFlags.of()),
                Paths.get("test.o"),
                new FakeSourcePath(filesystem, "test.cpp"),
                CxxSource.Type.CXX,
                CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER,
                strategy));
        break;
      default:
        throw new RuntimeException("Invalid strategy");
    }

    CxxCompilationDatabase compilationDatabase = CxxCompilationDatabase.createCompilationDatabase(
        testBuildRuleParams,
        testSourcePathResolver,
        strategy,
        rules.build());

    assertEquals(
        "getPathToOutput() should be a function of the build target.",
        BuildTargets.getGenPath(testBuildTarget, "__%s.json"),
        compilationDatabase.getPathToOutput());

    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    BuildableContext buildableContext = new FakeBuildableContext();
    List<Step> buildSteps = compilationDatabase.getPostBuildSteps(buildContext, buildableContext);
    assertEquals(2, buildSteps.size());
    assertTrue(buildSteps.get(0) instanceof MkdirStep);
    assertTrue(buildSteps.get(1) instanceof
            CxxCompilationDatabase.GenerateCompilationCommandsJson);

    CxxCompilationDatabase.GenerateCompilationCommandsJson step =
        (CxxCompilationDatabase.GenerateCompilationCommandsJson) buildSteps.get(1);
    Iterable<CxxCompilationDatabaseEntry> observedEntries =
        step.createEntries();
    Iterable<CxxCompilationDatabaseEntry> expectedEntries =
        ImmutableList.of(
          CxxCompilationDatabaseEntry.of(
              root,
              root + "/test.cpp",
              expectedArguments));
    MoreAsserts.assertIterablesEquals(expectedEntries, observedEntries);
  }

  @Test
  public void testCompilationDatabaseWithCombinedPreprocessAndCompileStrategy() {
    runCombinedTest(CxxPreprocessMode.COMBINED,
        ImmutableList.of(
            "compiler",
            "-I",
            "foo/bar",
            "-I",
            "test",
            "-x",
            "c++",
            "-c",
            "-MD",
            "-MF",
            "test.o.dep.tmp",
            "test.cpp",
            "-o",
            "test.o"));
  }

  @Test
  public void testCompilationDatabaseWithPipedPreprocessAndCompileStrategy() {
    runCombinedTest(CxxPreprocessMode.PIPED,
        ImmutableList.of(
            "compiler",
            "-I",
            "foo/bar",
            "-I",
            "test",
            "-x",
            "c++",
            "-c",
            "-o",
            "test.o",
            "/Users/user/src/test.cpp"));
  }

  @Test
  public void testCompilationDatabaseWithSeparatedPreprocessAndCompileStrategy() {
    runCombinedTest(CxxPreprocessMode.SEPARATE,
        ImmutableList.of(
            "compiler",
            "-I", "foo/bar",
            "-I", "test",
            // compdb will present a single command despite this being two commands under the hood,
            // hence, this is compiling a cpp file, not cpp preprocessed output.
            "-x", "c++",
            "-c",
            "-o",
            "test.o",
            "/Users/user/src/test.cpp"));
  }
}
