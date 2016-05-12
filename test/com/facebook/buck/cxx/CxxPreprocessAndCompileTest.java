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

import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxPreprocessAndCompileTest {

  private static class PreprocessorWithColorSupport
      extends DefaultPreprocessor {

    static final String COLOR_FLAG = "-use-color-in-preprocessor";

    public PreprocessorWithColorSupport(Tool tool) {
      super(tool);
    }

    @Override
    public Optional<ImmutableList<String>> getFlagsForColorDiagnostics() {
      return Optional.of(ImmutableList.of(COLOR_FLAG));
    }
  }

  private static class CompilerWithColorSupport
      extends DefaultCompiler {

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
      new DefaultPreprocessor(new HashedFileTool(Paths.get("preprocessor")));
  private static final Compiler DEFAULT_COMPILER =
      new DefaultCompiler(new HashedFileTool(Paths.get("compiler")));
  private static final Preprocessor PREPROCESSOR_WITH_COLOR_SUPPORT =
      new PreprocessorWithColorSupport(new HashedFileTool(Paths.get("preprocessor")));
  private static final Compiler COMPILER_WITH_COLOR_SUPPORT =
      new CompilerWithColorSupport(new HashedFileTool(Paths.get("compiler")));
  private static final CxxToolFlags DEFAULT_TOOL_FLAGS = CxxToolFlags.explicitBuilder()
      .addPlatformFlags("-fsanitize=address")
      .addRuleFlags("-O3")
      .build();
  private static final Path DEFAULT_OUTPUT = Paths.get("test.o");
  private static final SourcePath DEFAULT_INPUT = new FakeSourcePath("test.cpp");
  private static final CxxSource.Type DEFAULT_INPUT_TYPE = CxxSource.Type.CXX;
  private static final ImmutableList<CxxHeaders> DEFAULT_INCLUDES =
      ImmutableList.<CxxHeaders>of(
          CxxSymlinkTreeHeaders.builder()
              .setIncludeType(CxxPreprocessables.IncludeType.LOCAL)
              .setRoot(new BuildTargetSourcePath(BuildTargetFactory.newInstance("//:include")))
              .putNameToPathMap(Paths.get("test.h"), new FakeSourcePath("foo/test.h"))
              .build());
  private static final DebugPathSanitizer DEFAULT_SANITIZER =
      CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER;
  private static final Path DEFAULT_WORKING_DIR = Paths.get(System.getProperty("user.dir"));
  private static final
  RuleKeyAppendableFunction<FrameworkPath, Path> DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION =
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
  public void inputChangesCauseRuleKeyChangesForCompilation() {
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(
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

    RuleKey defaultRuleKey = new DefaultRuleKeyBuilderFactory(hashCache, pathResolver).build(
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            new CompilerDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_COMPILER,
                DEFAULT_TOOL_FLAGS),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));

    // Verify that changing the compiler causes a rulekey change.

    RuleKey compilerChange = new DefaultRuleKeyBuilderFactory(hashCache, pathResolver).build(
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            new CompilerDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                new DefaultCompiler(new HashedFileTool(Paths.get("different"))),
                DEFAULT_TOOL_FLAGS),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, compilerChange);

    // Verify that changing the operation causes a rulekey change.

    RuleKey operationChange = new DefaultRuleKeyBuilderFactory(hashCache, pathResolver).build(
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                CxxPlatformUtils.DEFAULT_CONFIG.getHeaderVerification(),
                DEFAULT_WORKING_DIR,
                DEFAULT_PREPROCESSOR,
                PreprocessorFlags.builder().build(),
                DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                DEFAULT_INCLUDES),
            new CompilerDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_COMPILER,
                DEFAULT_TOOL_FLAGS),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, operationChange);

    // Verify that changing the platform flags causes a rulekey change.

    RuleKey platformFlagsChange = new DefaultRuleKeyBuilderFactory(hashCache, pathResolver).build(
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            new CompilerDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_COMPILER,
                CxxToolFlags.explicitBuilder()
                    .addPlatformFlags("-different")
                    .setRuleFlags(DEFAULT_TOOL_FLAGS.getRuleFlags())
                    .build()),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, platformFlagsChange);

    // Verify that changing the rule flags causes a rulekey change.

    RuleKey ruleFlagsChange = new DefaultRuleKeyBuilderFactory(hashCache, pathResolver).build(
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            new CompilerDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_COMPILER,
                CxxToolFlags.explicitBuilder()
                    .setPlatformFlags(DEFAULT_TOOL_FLAGS.getPlatformFlags())
                    .addRuleFlags("-other", "flags")
                    .build()),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, ruleFlagsChange);

    // Verify that changing the input causes a rulekey change.

    RuleKey inputChange = new DefaultRuleKeyBuilderFactory(hashCache, pathResolver).build(
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            new CompilerDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_COMPILER,
                DEFAULT_TOOL_FLAGS),
            DEFAULT_OUTPUT,
            new FakeSourcePath("different"),
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, inputChange);
  }

  @Test
  public void preprocessorFlagsRuleKeyChangesCauseRuleKeyChangesForPreprocessing() {
    final SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    final BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    final FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(
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
      public RuleKey generate(PreprocessorFlags flags) {
        return new DefaultRuleKeyBuilderFactory(hashCache, pathResolver).build(
            CxxPreprocessAndCompile.preprocess(
                params,
                pathResolver,
                new PreprocessorDelegate(
                    pathResolver,
                    DEFAULT_SANITIZER,
                    CxxPlatformUtils.DEFAULT_CONFIG.getHeaderVerification(),
                    DEFAULT_WORKING_DIR,
                    DEFAULT_PREPROCESSOR,
                    flags,
                    DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                    DEFAULT_INCLUDES),
                new CompilerDelegate(
                    pathResolver,
                    DEFAULT_SANITIZER,
                    DEFAULT_COMPILER,
                    CxxToolFlags.of()),
                DEFAULT_OUTPUT,
                DEFAULT_INPUT,
                DEFAULT_INPUT_TYPE,
                DEFAULT_SANITIZER));
      }
    }
    TestData testData = new TestData();

    PreprocessorFlags defaultFlags = PreprocessorFlags.builder().build();
    PreprocessorFlags alteredFlags =
        defaultFlags.withFrameworkPaths(
            FrameworkPath.ofSourcePath(new FakeSourcePath("different")));
    assertNotEquals(testData.generate(defaultFlags), testData.generate(alteredFlags));
  }

  @Test
  public void usesCorrectCommandForCompile() {
    // Setup some dummy values for inputs to the CxxPreprocessAndCompile.
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    CxxToolFlags flags = CxxToolFlags.explicitBuilder()
        .addPlatformFlags("-ffunction-sections")
        .addRuleFlags("-O3")
        .build();
    Path output = Paths.get("test.o");
    Path input = Paths.get("test.ii");
    Path scratchDir = Paths.get("scratch");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            new CompilerDelegate(pathResolver, DEFAULT_SANITIZER, DEFAULT_COMPILER, flags),
            output,
            new FakeSourcePath(input.toString()),
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER);

    ImmutableList<String> expectedCompileCommand = ImmutableList.<String>builder()
        .add("compiler")
        .add("-ffunction-sections")
        .add("-O3")
        .add("-x", "c++")
        .add("-c")
        .add("-MD")
        .add("-MF")
        .add(params.getProjectFilesystem().resolve(scratchDir).resolve("dep.tmp").toString())
        .add(input.toString())
        .add("-o", output.toString())
        .build();
    ImmutableList<String> actualCompileCommand = buildRule.makeMainStep(scratchDir).getCommand();
    assertEquals(expectedCompileCommand, actualCompileCommand);
  }

  @Test
  public void usesCorrectCommandForPreprocess() {

    // Setup some dummy values for inputs to the CxxPreprocessAndCompile.
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    CxxToolFlags preprocessorFlags = CxxToolFlags.explicitBuilder()
        .addPlatformFlags("-Dtest=blah")
        .addRuleFlags("-Dfoo=bar")
        .build();
    Path output = Paths.get("test.ii");
    Path input = Paths.get("test.cpp");
    Path prefixHeader = Paths.get("prefix.pch");
    Path scratchDir = Paths.get("scratch");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                CxxPlatformUtils.DEFAULT_CONFIG.getHeaderVerification(),
                DEFAULT_WORKING_DIR,
                DEFAULT_PREPROCESSOR,
                PreprocessorFlags.builder()
                    .setOtherFlags(preprocessorFlags)
                    .setPrefixHeader(new FakeSourcePath(filesystem, prefixHeader.toString()))
                    .build(),
                DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                ImmutableList.<CxxHeaders>of()),
            new CompilerDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_COMPILER,
                CxxToolFlags.of()),
            output,
            new FakeSourcePath(input.toString()),
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER);

    // Verify it uses the expected command.
    ImmutableList<String> expectedPreprocessCommand = ImmutableList.<String>builder()
        .add("preprocessor")
        .add("-Dtest=blah")
        .add("-Dfoo=bar")
        .add("-include")
        .add(filesystem.resolve(prefixHeader).toString())
        .add("-x", "c++")
        .add("-E")
        .add("-MD")
        .add("-MF")
        .add(filesystem.resolve(scratchDir).resolve("dep.tmp").toString())
        .add(input.toString())
        .build();
    ImmutableList<String> actualPreprocessCommand = buildRule.makeMainStep(scratchDir).getCommand();
    assertEquals(expectedPreprocessCommand, actualPreprocessCommand);
  }

  @Test
  public void compilerAndPreprocessorAreAlwaysReturnedFromGetInputsAfterBuildingLocally()
      throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    SourcePath preprocessor = new PathSourcePath(filesystem, Paths.get("preprocessor"));
    Tool preprocessorTool =
        new CommandTool.Builder()
            .addInput(preprocessor)
            .build();

    SourcePath compiler = new PathSourcePath(filesystem, Paths.get("compiler"));
    Tool compilerTool =
        new CommandTool.Builder()
            .addInput(compiler)
            .build();

    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();

    CxxPreprocessAndCompile cxxPreprocess =
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                CxxPlatformUtils.DEFAULT_CONFIG.getHeaderVerification(),
                DEFAULT_WORKING_DIR,
                new DefaultPreprocessor(preprocessorTool),
                PreprocessorFlags.builder().build(),
                DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                ImmutableList.<CxxHeaders>of()),
            new CompilerDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_COMPILER,
                CxxToolFlags.of()),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER);
    assertThat(
        cxxPreprocess.getInputsAfterBuildingLocally(),
        hasItem(preprocessor));

    CxxPreprocessAndCompile cxxCompile =
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            new CompilerDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                new DefaultCompiler(compilerTool),
                CxxToolFlags.of()),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER);
    assertThat(
        cxxCompile.getInputsAfterBuildingLocally(),
        hasItem(compiler));
  }

  @Test
  public void usesColorFlagForCompilationWhenRequested() {
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    Path output = Paths.get("test.o");
    Path input = Paths.get("test.ii");
    Path scratchDir = Paths.get("scratch");

    CompilerDelegate compilerDelegate = new CompilerDelegate(
        pathResolver,
        DEFAULT_SANITIZER,
        COMPILER_WITH_COLOR_SUPPORT,
        CxxToolFlags.of());

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            compilerDelegate,
            output,
            new FakeSourcePath(input.toString()),
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER);

    ImmutableList<String> command =
        buildRule.makeMainStep(buildRule.getProjectFilesystem().getRootPath())
            .makeCompileCommand(
                input.toString(),
                "c++",
                /* preprocessable */ true,
                /* allowColorsInDiagnostics */ false);
    assertThat(command, not(hasItem(CompilerWithColorSupport.COLOR_FLAG)));

    command =
        buildRule.makeMainStep(scratchDir)
            .makeCompileCommand(
                input.toString(),
                "c++",
                /* preprocessable */ true,
                /* allowColorsInDiagnostics */ true);
    assertThat(command, hasItem(CompilerWithColorSupport.COLOR_FLAG));
  }

  @Test
  public void usesColorFlagForPreprocessingWhenRequested() {
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    Path output = Paths.get("test.ii");
    Path input = Paths.get("test.cpp");
    Path scratchDir = Paths.get("scratch");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                CxxPlatformUtils.DEFAULT_CONFIG.getHeaderVerification(),
                DEFAULT_WORKING_DIR,
                PREPROCESSOR_WITH_COLOR_SUPPORT,
                PreprocessorFlags.builder().build(),
                DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                ImmutableList.<CxxHeaders>of()),
            new CompilerDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_COMPILER,
                CxxToolFlags.of()),
            output,
            new FakeSourcePath(input.toString()),
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER);

    ImmutableList<String> command =
        buildRule.makeMainStep(scratchDir)
            .makePreprocessCommand(/* allowColorsInDiagnostics */ false);
    assertThat(command, not(hasItem(PreprocessorWithColorSupport.COLOR_FLAG)));

    command =
        buildRule.makeMainStep(scratchDir)
            .makePreprocessCommand(/* allowColorsInDiagnostics */ true);
    assertThat(command, hasItem(PreprocessorWithColorSupport.COLOR_FLAG));
  }
}
