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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig.ToolType;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.DefaultCompiler;
import com.facebook.buck.cxx.toolchain.GccCompiler;
import com.facebook.buck.cxx.toolchain.GccPreprocessor;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.args.AddsToRuleKeyFunction;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.PathNormalizer;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
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
  }

  private static class CompilerWithColorSupport extends DefaultCompiler {

    static final String COLOR_FLAG = "-use-color-in-compiler";

    public CompilerWithColorSupport(Tool tool) {
      super(tool, false);
    }

    @Override
    public Optional<ImmutableList<String>> getFlagsForColorDiagnostics() {
      return Optional.of(ImmutableList.of(COLOR_FLAG));
    }
  }

  private static final Optional<Boolean> DEFAULT_USE_ARG_FILE = Optional.empty();
  private static final CxxToolFlags DEFAULT_TOOL_FLAGS =
      CxxToolFlags.explicitBuilder()
          .addPlatformFlags(StringArg.of("-fsanitize=address"))
          .addRuleFlags(StringArg.of("-O3"))
          .build();
  private static final String DEFAULT_OUTPUT = "test.o";
  private static final SourcePath DEFAULT_INPUT = FakeSourcePath.of("test.cpp");
  private static final CxxSource.Type DEFAULT_INPUT_TYPE = CxxSource.Type.CXX;
  private static final PathSourcePath DEFAULT_WORKING_DIR =
      FakeSourcePath.of(System.getProperty("user.dir"));
  private static final AddsToRuleKeyFunction<FrameworkPath, Path>
      DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION = new DefaultFramworkPathSearchPathFunction();

  private static class DefaultFramworkPathSearchPathFunction
      implements AddsToRuleKeyFunction<FrameworkPath, Path> {

    @Override
    public Path apply(FrameworkPath input) {
      return Paths.get("test", "framework", "path", input.toString());
    }
  }

  private ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

  private Preprocessor DEFAULT_PREPROCESSOR =
      new GccPreprocessor(
          new HashedFileTool(
              () ->
                  PathSourcePath.of(
                      projectFilesystem,
                      PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/preprocessor")))));
  private Compiler DEFAULT_COMPILER =
      new GccCompiler(
          new HashedFileTool(
              () ->
                  PathSourcePath.of(
                      projectFilesystem,
                      PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")))),
          ToolType.CXX,
          false,
          false);
  private Preprocessor PREPROCESSOR_WITH_COLOR_SUPPORT =
      new PreprocessorWithColorSupport(
          new HashedFileTool(
              () ->
                  PathSourcePath.of(
                      projectFilesystem,
                      PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/preprocessor")))));
  private Compiler COMPILER_WITH_COLOR_SUPPORT =
      new CompilerWithColorSupport(
          new HashedFileTool(
              () ->
                  PathSourcePath.of(
                      projectFilesystem,
                      PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")))));

  @Test
  public void inputChangesCauseRuleKeyChangesForCompilation() {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(
            ImmutableMap.<String, String>builder()
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/preprocessor"))
                        .toString(),
                    Strings.repeat("a", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")).toString(),
                    Strings.repeat("a", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("test.o")).toString(),
                    Strings.repeat("b", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("test.cpp")).toString(),
                    Strings.repeat("c", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/different")).toString(),
                    Strings.repeat("d", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("foo/test.h")).toString(),
                    Strings.repeat("e", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("path/to/a/plugin.so"))
                        .toString(),
                    Strings.repeat("f", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("path/to/a/different/plugin.so"))
                        .toString(),
                    Strings.repeat("a0", 40))
                .build());

    // Generate a rule key for the defaults.

    RuleKey defaultRuleKey =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        DEFAULT_TOOL_FLAGS,
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER));

    // Verify that changing the compiler causes a rulekey change.

    RuleKey compilerChange =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        new GccCompiler(
                            new HashedFileTool(
                                PathSourcePath.of(
                                    projectFilesystem,
                                    PathNormalizer.toWindowsPathIfNeeded(
                                        Paths.get("/root/different")))),
                            ToolType.CXX,
                            false),
                        DEFAULT_TOOL_FLAGS,
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER));
    assertNotEquals(defaultRuleKey, compilerChange);

    // Verify that changing the operation causes a rulekey change.

    RuleKey operationChange =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.preprocessAndCompile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new PreprocessorDelegate(
                        CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                        DEFAULT_WORKING_DIR,
                        DEFAULT_PREPROCESSOR,
                        PreprocessorFlags.builder().build(),
                        DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                        /* leadingIncludePaths */ Optional.empty(),
                        Optional.of(
                            new FakeBuildRule(target.withFlavors(InternalFlavor.of("deps")))),
                        ImmutableSortedSet.of()),
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        DEFAULT_TOOL_FLAGS,
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    Optional.empty(),
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER));
    assertNotEquals(defaultRuleKey, operationChange);

    // Verify that changing the platform flags causes a rulekey change.

    RuleKey platformFlagsChange =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        CxxToolFlags.explicitBuilder()
                            .addPlatformFlags(StringArg.of("-different"))
                            .setRuleFlags(DEFAULT_TOOL_FLAGS.getRuleFlags())
                            .build(),
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER));
    assertNotEquals(defaultRuleKey, platformFlagsChange);

    // Verify that changing the rule flags causes a rulekey change.

    RuleKey ruleFlagsChange =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        CxxToolFlags.explicitBuilder()
                            .setPlatformFlags(DEFAULT_TOOL_FLAGS.getPlatformFlags())
                            .addAllRuleFlags(StringArg.from("-other", "flags"))
                            .build(),
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER));
    assertNotEquals(defaultRuleKey, ruleFlagsChange);

    // Verify that changing the input causes a rulekey change.

    RuleKey inputChange =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.compile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        DEFAULT_TOOL_FLAGS,
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    FakeSourcePath.of(
                        PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/different"))),
                    DEFAULT_INPUT_TYPE,
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER));
    assertNotEquals(defaultRuleKey, inputChange);
  }

  @Test
  public void preprocessorFlagsRuleKeyChangesCauseRuleKeyChangesForPreprocessing() {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(
            ImmutableMap.<String, String>builder()
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/preprocessor"))
                        .toString(),
                    Strings.repeat("a", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")).toString(),
                    Strings.repeat("a", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("test.o")).toString(),
                    Strings.repeat("b", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("test.cpp")).toString(),
                    Strings.repeat("c", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/different")).toString(),
                    Strings.repeat("d", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("foo/test.h")).toString(),
                    Strings.repeat("e", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("path/to/a/plugin.so"))
                        .toString(),
                    Strings.repeat("f", 40))
                .put(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("path/to/a/different/plugin.so"))
                        .toString(),
                    Strings.repeat("a0", 40))
                .build());

    class TestData {
      public RuleKey generate(PreprocessorFlags flags) {
        return new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                CxxPreprocessAndCompile.preprocessAndCompile(
                    target,
                    projectFilesystem,
                    ruleFinder,
                    new PreprocessorDelegate(
                        CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                        DEFAULT_WORKING_DIR,
                        DEFAULT_PREPROCESSOR,
                        flags,
                        DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                        /* leadingIncludePaths */ Optional.empty(),
                        Optional.of(
                            new FakeBuildRule(target.withFlavors(InternalFlavor.of("deps")))),
                        ImmutableSortedSet.of()),
                    new CompilerDelegate(
                        CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                        DEFAULT_COMPILER,
                        CxxToolFlags.of(),
                        DEFAULT_USE_ARG_FILE),
                    DEFAULT_OUTPUT,
                    DEFAULT_INPUT,
                    DEFAULT_INPUT_TYPE,
                    Optional.empty(),
                    CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER));
      }
    }
    TestData testData = new TestData();

    PreprocessorFlags defaultFlags = PreprocessorFlags.builder().build();
    PreprocessorFlags alteredFlags =
        defaultFlags.withFrameworkPaths(
            FrameworkPath.ofSourcePath(
                FakeSourcePath.of(
                    PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/different")))));
    assertNotEquals(testData.generate(defaultFlags), testData.generate(alteredFlags));
  }

  @Test
  public void usesCorrectCommandForCompile() {
    // Setup some dummy values for inputs to the CxxPreprocessAndCompile.
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    CxxToolFlags flags =
        CxxToolFlags.explicitBuilder()
            .addPlatformFlags(StringArg.of("-ffunction-sections"))
            .addRuleFlags(StringArg.of("-O3"))
            .build();
    String outputName = "test.o";
    Path input = Paths.get("test.ii");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.compile(
            target,
            projectFilesystem,
            ruleFinder,
            new CompilerDelegate(
                NoopDebugPathSanitizer.INSTANCE, DEFAULT_COMPILER, flags, DEFAULT_USE_ARG_FILE),
            outputName,
            FakeSourcePath.of(input.toString()),
            DEFAULT_INPUT_TYPE,
            NoopDebugPathSanitizer.INSTANCE);

    ImmutableList<String> expectedCompileCommand =
        ImmutableList.<String>builder()
            .add(PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")).toString())
            .add("-x", "c++")
            .add("-ffunction-sections")
            .add("-O3")
            .add(
                "-o",
                PathNormalizer.toWindowsPathIfNeeded(Paths.get("buck-out/gen/foo/bar__/test.o"))
                    .toString())
            .add("-c")
            .add(input.toString())
            .build();
    ImmutableList<String> actualCompileCommand =
        buildRule.makeMainStep(context, false).getCommand();
    assertEquals(expectedCompileCommand, actualCompileCommand);
  }

  @Test
  public void compilerAndPreprocessorAreAlwaysReturnedFromGetInputsAfterBuildingLocally()
      throws Exception {
    CellPathResolver cellPathResolver = TestCellPathResolver.get(projectFilesystem);
    SourcePath preprocessor = FakeSourcePath.of(projectFilesystem, "preprocessor");
    Tool preprocessorTool = new CommandTool.Builder().addInput(preprocessor).build();

    SourcePath compiler = FakeSourcePath.of(projectFilesystem, "compiler");
    Tool compilerTool = new CommandTool.Builder().addInput(compiler).build();

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);

    projectFilesystem.writeContentsToPath(
        "test.o: " + pathResolver.getRelativePath(DEFAULT_INPUT) + " ",
        projectFilesystem.getPath(
            PathNormalizer.toWindowsPathIfNeeded(Paths.get("buck-out/gen/foo/bar__/test.o.dep"))
                .toString()));
    PathSourcePath fakeInput = FakeSourcePath.of(projectFilesystem, "test.cpp");

    CxxPreprocessAndCompile cxxPreprocess =
        CxxPreprocessAndCompile.preprocessAndCompile(
            target,
            projectFilesystem,
            ruleFinder,
            new PreprocessorDelegate(
                CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                DEFAULT_WORKING_DIR,
                new GccPreprocessor(preprocessorTool),
                PreprocessorFlags.builder().build(),
                DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                /* leadingIncludePaths */ Optional.empty(),
                Optional.of(new FakeBuildRule(target.withFlavors(InternalFlavor.of("deps")))),
                ImmutableSortedSet.of()),
            new CompilerDelegate(
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                DEFAULT_COMPILER,
                CxxToolFlags.of(),
                DEFAULT_USE_ARG_FILE),
            DEFAULT_OUTPUT,
            fakeInput,
            DEFAULT_INPUT_TYPE,
            Optional.empty(),
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER);
    assertThat(
        cxxPreprocess.getInputsAfterBuildingLocally(context, cellPathResolver),
        not(hasItem(preprocessor)));
    assertFalse(cxxPreprocess.getCoveredByDepFilePredicate(pathResolver).test(preprocessor));

    CxxPreprocessAndCompile cxxCompile =
        CxxPreprocessAndCompile.compile(
            target,
            projectFilesystem,
            ruleFinder,
            new CompilerDelegate(
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                new GccCompiler(compilerTool, ToolType.CXX, false),
                CxxToolFlags.of(),
                DEFAULT_USE_ARG_FILE),
            DEFAULT_OUTPUT,
            fakeInput,
            DEFAULT_INPUT_TYPE,
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER);
    assertThat(
        cxxCompile.getInputsAfterBuildingLocally(context, cellPathResolver),
        not(hasItem(compiler)));
    assertFalse(cxxCompile.getCoveredByDepFilePredicate(pathResolver).test(compiler));
  }

  @Test
  public void usesColorFlagForCompilationWhenRequested() {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    String output = "test.o";
    Path input = Paths.get("test.ii");

    CompilerDelegate compilerDelegate =
        new CompilerDelegate(
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            COMPILER_WITH_COLOR_SUPPORT,
            CxxToolFlags.of(),
            DEFAULT_USE_ARG_FILE);

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.compile(
            target,
            projectFilesystem,
            ruleFinder,
            compilerDelegate,
            output,
            FakeSourcePath.of(input.toString()),
            DEFAULT_INPUT_TYPE,
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER);

    ImmutableList<String> command =
        buildRule.makeMainStep(context, false).getArguments(/* allowColorsInDiagnostics */ false);
    assertThat(command, not(hasItem(CompilerWithColorSupport.COLOR_FLAG)));

    command =
        buildRule.makeMainStep(context, false).getArguments(/* allowColorsInDiagnostics */ true);
    assertThat(command, hasItem(CompilerWithColorSupport.COLOR_FLAG));
  }

  @Test
  public void usesColorFlagForPreprocessingWhenRequested() {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    String output = "test.ii";
    Path input = Paths.get("test.cpp");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.preprocessAndCompile(
            target,
            projectFilesystem,
            ruleFinder,
            new PreprocessorDelegate(
                CxxPlatformUtils.DEFAULT_PLATFORM.getHeaderVerification(),
                DEFAULT_WORKING_DIR,
                PREPROCESSOR_WITH_COLOR_SUPPORT,
                PreprocessorFlags.builder().build(),
                DEFAULT_FRAMEWORK_PATH_SEARCH_PATH_FUNCTION,
                /* leadingIncludePaths */ Optional.empty(),
                Optional.of(new FakeBuildRule(target.withFlavors(InternalFlavor.of("deps")))),
                ImmutableSortedSet.of()),
            new CompilerDelegate(
                CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
                COMPILER_WITH_COLOR_SUPPORT,
                CxxToolFlags.of(),
                DEFAULT_USE_ARG_FILE),
            output,
            FakeSourcePath.of(input.toString()),
            DEFAULT_INPUT_TYPE,
            Optional.empty(),
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER);

    ImmutableList<String> command =
        buildRule.makeMainStep(context, false).getArguments(/* allowColorsInDiagnostics */ false);
    assertThat(command, not(hasItem(PreprocessorWithColorSupport.COLOR_FLAG)));

    command =
        buildRule.makeMainStep(context, false).getArguments(/* allowColorsInDiagnostics */ true);
    assertThat(command, hasItem(CompilerWithColorSupport.COLOR_FLAG));
  }

  @Test
  public void testGetGcnoFile() {
    Path input =
        projectFilesystem.resolve(
            PathNormalizer.toWindowsPathIfNeeded(Paths.get("foo/bar.m.o")).toString());
    Path output = CxxPreprocessAndCompile.getGcnoPath(input);
    assertEquals(
        projectFilesystem.resolve(
            PathNormalizer.toWindowsPathIfNeeded(Paths.get("foo/bar.m.gcno"))),
        output);
  }

  @Test
  public void usesUnixPathSeparatorForCompile() {
    // Setup some dummy values for inputs to the CxxPreprocessAndCompile.
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildContext context = FakeBuildContext.withSourcePathResolver(pathResolver);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    Path includePath = PathNormalizer.toWindowsPathIfNeeded(Paths.get("/foo/bar/zap"));
    String includedPathStr = MorePaths.pathWithUnixSeparators(includePath);

    CxxToolFlags flags =
        CxxToolFlags.explicitBuilder()
            .addPlatformFlags(StringArg.of("-ffunction-sections"))
            .addRuleFlags(StringArg.of("-O3"))
            .addRuleFlags(StringArg.of("-I " + includedPathStr))
            .build();
    String outputName = "baz\\test.o";
    Path input = Paths.get("foo\\test.ii");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.compile(
            target,
            projectFilesystem,
            ruleFinder,
            new CompilerDelegate(
                NoopDebugPathSanitizer.INSTANCE,
                new GccCompiler(
                    new HashedFileTool(
                        () ->
                            PathSourcePath.of(
                                projectFilesystem,
                                PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")))),
                    ToolType.CXX,
                    false),
                flags,
                DEFAULT_USE_ARG_FILE),
            outputName,
            FakeSourcePath.of(input.toString()),
            DEFAULT_INPUT_TYPE,
            NoopDebugPathSanitizer.INSTANCE);

    ImmutableList<String> expectedCompileCommand =
        ImmutableList.<String>builder()
            .add(PathNormalizer.toWindowsPathIfNeeded(Paths.get("/root/compiler")).toString())
            .add("-x", "c++")
            .add("-ffunction-sections")
            .add("-O3")
            .add("-I " + MorePaths.pathWithUnixSeparators(includePath))
            .add("-o", "buck-out/gen/foo/bar__/baz/test.o")
            .add("-c")
            .add(MorePaths.pathWithUnixSeparators(input.toString()))
            .build();
    ImmutableList<String> actualCompileCommand =
        buildRule.makeMainStep(context, false).getCommand();
    assertEquals(expectedCompileCommand, actualCompileCommand);
  }
}
