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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.DefaultCompiler;
import com.facebook.buck.cxx.toolchain.GccCompiler;
import com.facebook.buck.cxx.toolchain.GccPreprocessor;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildContext;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SingleThreadedBuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.TestCellPathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class CxxPreprocessAndCompileTest {

  private static class PreprocessorWithColorSupport extends GccPreprocessor {

    static final String COLOR_FLAG = "-use-color-in-preprocessor";

    public PreprocessorWithColorSupport(Tool tool) {
      super(tool);
    }

    @Override
    public Optional<ImmutableList<String>> getFlagsForColorDiagnostics() {
      return Optional.of(ImmutableList.of(COLOR_FLAG));
    }
  }

  private static class CompilerWithColorSupport extends DefaultCompiler {

    static final String COLOR_FLAG = "-use-color-in-compiler";

    public CompilerWithColorSupport(Tool tool) {
      super(tool);
    }

    @Override
    public Optional<ImmutableList<String>> getFlagsForColorDiagnostics() {
      return Optional.of(ImmutableList.of(COLOR_FLAG));
    }
  }

  private static final Preprocessor DEFAULT_PREPROCESSOR =
      new GccPreprocessor(new HashedFileTool(Paths.get("preprocessor")));
  private static final Compiler DEFAULT_COMPILER =
      new GccCompiler(new HashedFileTool(Paths.get("compiler")));
  private static final Preprocessor PREPROCESSOR_WITH_COLOR_SUPPORT =
      new PreprocessorWithColorSupport(new HashedFileTool(Paths.get("preprocessor")));
  private static final Compiler COMPILER_WITH_COLOR_SUPPORT =
      new CompilerWithColorSupport(new HashedFileTool(Paths.get("compiler")));
  private static final CxxToolFlags DEFAULT_TOOL_FLAGS =
      CxxToolFlags.explicitBuilder()
          .addPlatformFlags(StringArg.of("-fsanitize=address"))
          .addRuleFlags(StringArg.of("-O3"))
          .build();
  private static final Path DEFAULT_OUTPUT = Paths.get("test.o");
  private static final SourcePath DEFAULT_INPUT = FakeSourcePath.of("test.cpp");
  private static final CxxSource.Type DEFAULT_INPUT_TYPE = CxxSource.Type.CXX;
  private static final Path DEFAULT_WORKING_DIR = Paths.get(System.getProperty("user.dir"));
  private static final RuleKeyAppendableFunction<FrameworkPath, Path>
      DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION =
          new RuleKeyAppendableFunction<FrameworkPath, Path>() {
            @Override
            public void appendToRuleKey(RuleKeyObjectSink sink) {
              // Do nothing.
            }

            @Override
            public Path apply(FrameworkPath input) {
              return Paths.get("test", "framework", "path", input.toString());
            }
          };

  @Test
  public void inputChangesCauseRuleKeyChangesForCompilation() throws Exception {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new SingleThreadedBuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params = TestBuildRuleParams.create();
    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(
            ImmutableMap.<String, String>builder()
                .put("preprocessor", Strings.repeat("a", 40))
                .put("compiler", Strings.repeat("a", 40))
                .put("test.o", Strings.repeat("b", 40))
                .put("test.cpp", Strings.repeat("c", 40))
                .put("different", Strings.repeat("d", 40))
                .put("foo/test.h", Strings.repeat("e", 40))
                .put("path/to/a/plugin.so", Strings.repeat("f", 40))
                .put("path/to/a/different/plugin.so", Strings.repeat("a0", 40))
                .build());

    // Generate a rule key for the defaults.

    RuleKey defaultRuleKey =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    params,
                    new CompilerDelegate(
                        pathResolver,
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        DEFAULT_TOOL_FLAGS),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    Optional.empty()));

    // Verify that changing the compiler causes a rulekey change.

    RuleKey compilerChange =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    params,
                    new CompilerDelegate(
                        pathResolver,
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        new GccCompiler(new HashedFileTool(Paths.get("different"))),
                        DEFAULT_TOOL_FLAGS),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    Optional.empty()));
    assertNotEquals(defaultRuleKey, compilerChange);

    // Verify that changing the operation causes a rulekey change.

    RuleKey operationChange =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.preprocessAndCompile(
                    target,
                    projectFilesystem,
                    params,
                    new PreprocessorDelegate(
                        pathResolver,
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                        DEFAULT_WORKING_DIR,
                        DEFAULT_PREPROCESSOR,
                        PreprocessorFlags.builder().build(),
                        DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                        Optional.empty(),
                        /* leadingIncludePaths */ Optional.empty()),
                    new CompilerDelegate(
                        pathResolver,
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        DEFAULT_TOOL_FLAGS),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    Optional.empty(),
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    Optional.empty()));
    assertNotEquals(defaultRuleKey, operationChange);

    // Verify that changing the platform flags causes a rulekey change.

    RuleKey platformFlagsChange =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    params,
                    new CompilerDelegate(
                        pathResolver,
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        CxxToolFlags.explicitBuilder()
                            .addPlatformFlags(StringArg.of("-different"))
                            .setRuleFlags(DEFAULT_TOOL_FLAGS.getRuleFlags())
                            .build()),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    Optional.empty()));
    assertNotEquals(defaultRuleKey, platformFlagsChange);

    // Verify that changing the rule flags causes a rulekey change.

    RuleKey ruleFlagsChange =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    params,
                    new CompilerDelegate(
                        pathResolver,
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        CxxToolFlags.explicitBuilder()
                            .setPlatformFlags(DEFAULT_TOOL_FLAGS.getPlatformFlags())
                            .addAllRuleFlags(StringArg.from("-other", "flags"))
                            .build()),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    Optional.empty()));
    assertNotEquals(defaultRuleKey, ruleFlagsChange);

    // Verify that changing the input causes a rulekey change.

    RuleKey inputChange =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    params,
                    new CompilerDelegate(
                        pathResolver,
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        DEFAULT_TOOL_FLAGS),
                    DEFAULT_OUTPUT,
                    FakeSourcePath.of("different"),
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    Optional.empty()));
    assertNotEquals(defaultRuleKey, inputChange);
  }

  @Test
  public void preprocessorFlagsRuleKeyChangesCauseRuleKeyChangesForPreprocessing()
      throws Exception {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new SingleThreadedBuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    final SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    final BuildRuleParams params = TestBuildRuleParams.create();
    final FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(
            ImmutableMap.<String, String>builder()
                .put("preprocessor", Strings.repeat("a", 40))
                .put("compiler", Strings.repeat("a", 40))
                .put("test.o", Strings.repeat("b", 40))
                .put("test.cpp", Strings.repeat("c", 40))
                .put("different", Strings.repeat("d", 40))
                .put("foo/test.h", Strings.repeat("e", 40))
                .put("path/to/a/plugin.so", Strings.repeat("f", 40))
                .put("path/to/a/different/plugin.so", Strings.repeat("a0", 40))
                .build());

    class TestData {
      public RuleKey generate(PreprocessorFlags flags) throws Exception {
        return new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.preprocessAndCompile(
                    target,
                    projectFilesystem,
                    params,
                    new PreprocessorDelegate(
                        pathResolver,
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                        DEFAULT_WORKING_DIR,
                        DEFAULT_PREPROCESSOR,
                        flags,
                        DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                        Optional.empty(),
                        /* leadingIncludePaths */ Optional.empty()),
                    new CompilerDelegate(
                        pathResolver,
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        CxxToolFlags.of()),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    Optional.empty(),
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                    Optional.empty()));
      }
    }
    TestData testData = new TestData();

    PreprocessorFlags defaultFlags = PreprocessorFlags.builder().build();
    PreprocessorFlags alteredFlags =
        defaultFlags.withFrameworkPaths(FrameworkPath.ofSourcePath(FakeSourcePath.of("different")));
    assertNotEquals(testData.generate(defaultFlags), testData.generate(alteredFlags));
  }

  @Test
  public void usesCorrectCommandForCompile() {
    // Setup some dummy values for inputs to the CxxPreprocessAndCompile.
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(
            new SourcePathRuleFinder(
                new SingleThreadedBuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params = TestBuildRuleParams.create();
    CxxToolFlags flags =
        CxxToolFlags.explicitBuilder()
            .addPlatformFlags(StringArg.of("-ffunction-sections"))
            .addRuleFlags(StringArg.of("-O3"))
            .build();
    Path output = Paths.get("test.o");
    Path input = Paths.get("test.ii");
    Path scratchDir = Paths.get("scratch");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.compile(
            target,
            projectFilesystem,
            params,
            new CompilerDelegate(
                pathResolver,
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                DEFAULT_COMPILER,
                flags),
            output,
            FakeSourcePath.of(input.toString()),
            DEFAULT_INPUT_TYPE,
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            Optional.empty());

    ImmutableList<String> expectedCompileCommand =
        ImmutableList.<String>builder()
            .add("compiler")
            .add("-x", "c++")
            .add("-ffunction-sections")
            .add("-O3")
            .add("-c")
            .add(input.toString())
            .add("-o", output.toString())
            .build();
    ImmutableList<String> actualCompileCommand =
        buildRule.makeMainStep(pathResolver, scratchDir, false).getCommand();
    assertEquals(expectedCompileCommand, actualCompileCommand);
  }

  @Test
  public void compilerAndPreprocessorAreAlwaysReturnedFromGetInputsAfterBuildingLocally()
      throws Exception {
    ProjectFilesystem filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem();
    CellPathResolver cellPathResolver = TestCellPathResolver.get(filesystem);
    SourcePath preprocessor = FakeSourcePath.of(filesystem, "preprocessor");
    Tool preprocessorTool = new CommandTool.Builder().addInput(preprocessor).build();

    SourcePath compiler = FakeSourcePath.of(filesystem, "compiler");
    Tool compilerTool = new CommandTool.Builder().addInput(compiler).build();

    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(
            new SourcePathRuleFinder(
                new SingleThreadedBuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = TestBuildRuleParams.create();
    BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);

    filesystem.writeContentsToPath(
        "test.o: " + pathResolver.getRelativePath(DEFAULT_INPUT) + " ",
        filesystem.getPath("test.o.dep"));
    PathSourcePath fakeInput = FakeSourcePath.of(filesystem, "test.cpp");

    CxxPreprocessAndCompile cxxPreprocess =
        CxxPreprocessAndCompile.preprocessAndCompile(
            target,
            filesystem,
            params,
            new PreprocessorDelegate(
                pathResolver,
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                DEFAULT_WORKING_DIR,
                new GccPreprocessor(preprocessorTool),
                PreprocessorFlags.builder().build(),
                DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                Optional.empty(),
                /* leadingIncludePaths */ Optional.empty()),
            new CompilerDelegate(
                pathResolver,
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                DEFAULT_COMPILER,
                CxxToolFlags.of()),
            DEFAULT_OUTPUT,
            fakeInput,
            DEFAULT_INPUT_TYPE,
            Optional.empty(),
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            Optional.empty());
    assertThat(
        cxxPreprocess.getInputsAfterBuildingLocally(context, cellPathResolver),
        hasItem(preprocessor));

    CxxPreprocessAndCompile cxxCompile =
        CxxPreprocessAndCompile.compile(
            target,
            filesystem,
            params,
            new CompilerDelegate(
                pathResolver,
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                new GccCompiler(compilerTool),
                CxxToolFlags.of()),
            DEFAULT_OUTPUT,
            fakeInput,
            DEFAULT_INPUT_TYPE,
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            Optional.empty());
    assertThat(
        cxxCompile.getInputsAfterBuildingLocally(context, cellPathResolver), hasItem(compiler));
  }

  @Test
  public void usesColorFlagForCompilationWhenRequested() {
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(
            new SourcePathRuleFinder(
                new SingleThreadedBuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params = TestBuildRuleParams.create();
    Path output = Paths.get("test.o");
    Path input = Paths.get("test.ii");
    Path scratchDir = Paths.get("scratch");

    CompilerDelegate compilerDelegate =
        new CompilerDelegate(
            pathResolver,
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            COMPILER_WITH_COLOR_SUPPORT,
            CxxToolFlags.of());

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.compile(
            target,
            projectFilesystem,
            params,
            compilerDelegate,
            output,
            FakeSourcePath.of(input.toString()),
            DEFAULT_INPUT_TYPE,
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            Optional.empty());

    ImmutableList<String> command =
        buildRule
            .makeMainStep(pathResolver, buildRule.getProjectFilesystem().getRootPath(), false)
            .getArguments(/* allowColorsInDiagnostics */ false);
    assertThat(command, not(hasItem(CompilerWithColorSupport.COLOR_FLAG)));

    command =
        buildRule
            .makeMainStep(pathResolver, scratchDir, false)
            .getArguments(/* allowColorsInDiagnostics */ true);
    assertThat(command, hasItem(CompilerWithColorSupport.COLOR_FLAG));
  }

  @Test
  public void usesColorFlagForPreprocessingWhenRequested() throws Exception {
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(
            new SourcePathRuleFinder(
                new SingleThreadedBuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params = TestBuildRuleParams.create();
    Path output = Paths.get("test.ii");
    Path input = Paths.get("test.cpp");
    Path scratchDir = Paths.get("scratch");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.preprocessAndCompile(
            target,
            projectFilesystem,
            params,
            new PreprocessorDelegate(
                pathResolver,
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                DEFAULT_WORKING_DIR,
                PREPROCESSOR_WITH_COLOR_SUPPORT,
                PreprocessorFlags.builder().build(),
                DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                Optional.empty(),
                /* leadingIncludePaths */ Optional.empty()),
            new CompilerDelegate(
                pathResolver,
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                COMPILER_WITH_COLOR_SUPPORT,
                CxxToolFlags.of()),
            output,
            FakeSourcePath.of(input.toString()),
            DEFAULT_INPUT_TYPE,
            Optional.empty(),
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            Optional.empty());

    ImmutableList<String> command =
        buildRule
            .makeMainStep(pathResolver, scratchDir, false)
            .getArguments(/* allowColorsInDiagnostics */ false);
    assertThat(command, not(hasItem(PreprocessorWithColorSupport.COLOR_FLAG)));

    command =
        buildRule
            .makeMainStep(pathResolver, scratchDir, false)
            .getArguments(/* allowColorsInDiagnostics */ true);
    assertThat(command, hasItem(CompilerWithColorSupport.COLOR_FLAG));
  }

  @Test
  public void testGetGcnoFile() throws Exception {
    Path input = Paths.get("foo/bar.m.o");
    Path output = CxxPreprocessAndCompile.getGcnoPath(input);
    assertEquals(Paths.get("foo/bar.m.gcno"), output);
  }
}
