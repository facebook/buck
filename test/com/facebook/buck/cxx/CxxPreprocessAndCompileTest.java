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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxPreprocessAndCompileTest {

  private static final Preprocessor DEFAULT_PREPROCESSOR =
      new DefaultPreprocessor(new HashedFileTool(Paths.get("preprocessor")));
  private static final Compiler DEFAULT_COMPILER =
      new DefaultCompiler(new HashedFileTool(Paths.get("compiler")));
  private static final ImmutableList<String> DEFAULT_PLATFORM_FLAGS =
      ImmutableList.of("-fsanitize=address");
  private static final ImmutableList<String> DEFAULT_RULE_FLAGS =
      ImmutableList.of("-O3");
  private static final ImmutableList<String> DEFAULT_PREPROCESSOR_PLATFORM_FLAGS =
      ImmutableList.of();
  private static final ImmutableList<String> DEFAULT_PREPROCESOR_RULE_FLAGS =
      ImmutableList.of("-DTEST");
  private static final Path DEFAULT_OUTPUT = Paths.get("test.o");
  private static final SourcePath DEFAULT_INPUT = new FakeSourcePath("test.cpp");
  private static final CxxSource.Type DEFAULT_INPUT_TYPE = CxxSource.Type.CXX;
  private static final ImmutableList<CxxHeaders> DEFAULT_INCLUDES =
      ImmutableList.of(
          CxxHeaders.builder()
              .putNameToPathMap(Paths.get("test.h"), new FakeSourcePath("foo/test.h"))
              .build());
  private static final ImmutableSet<Path> DEFAULT_INCLUDE_ROOTS = ImmutableSet.of(
      Paths.get("foo/bar"),
      Paths.get("test"));
  private static final ImmutableSet<Path> DEFAULT_SYSTEM_INCLUDE_ROOTS = ImmutableSet.of(
      Paths.get("/usr/include"),
      Paths.get("/include"));
  private static final ImmutableSet<Path> DEFAULT_HEADER_MAPS = ImmutableSet.of(
      Paths.get("some/thing.hmap"),
      Paths.get("another/file.hmap"));
  private static final ImmutableSet<Path> DEFAULT_FRAMEWORK_ROOTS = ImmutableSet.of();
  private static final DebugPathSanitizer DEFAULT_SANITIZER =
      CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER;

  @Test
  public void inputChangesCauseRuleKeyChangesForCompilation() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
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
                    .build()),
            pathResolver);

    // Generate a rule key for the defaults.

    RuleKey defaultRuleKey = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            DEFAULT_PLATFORM_FLAGS,
            DEFAULT_RULE_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));

    // Verify that changing the compiler causes a rulekey change.

    RuleKey compilerChange = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            new DefaultCompiler(new HashedFileTool(Paths.get("different"))),
            DEFAULT_PLATFORM_FLAGS,
            DEFAULT_RULE_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, compilerChange);

    // Verify that changing the operation causes a rulekey change.

    RuleKey operationChange = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_PREPROCESSOR,
                DEFAULT_PLATFORM_FLAGS,
                DEFAULT_RULE_FLAGS,
                DEFAULT_INCLUDE_ROOTS,
                DEFAULT_SYSTEM_INCLUDE_ROOTS,
                DEFAULT_HEADER_MAPS,
                DEFAULT_FRAMEWORK_ROOTS,
                Optional.<SourcePath>absent(),
                DEFAULT_INCLUDES
            ),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, operationChange);

    // Verify that changing the platform flags causes a rulekey change.

    RuleKey platformFlagsChange = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            ImmutableList.of("-different"),
            DEFAULT_RULE_FLAGS,
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, platformFlagsChange);

    // Verify that changing the rule flags causes a rulekey change.

    RuleKey ruleFlagsChange = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            DEFAULT_PLATFORM_FLAGS,
            ImmutableList.of("-other", "flags"),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, ruleFlagsChange);

    // Verify that changing the input causes a rulekey change.

    RuleKey inputChange = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            DEFAULT_PLATFORM_FLAGS,
            DEFAULT_RULE_FLAGS,
            DEFAULT_OUTPUT,
            new FakeSourcePath("different"),
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, inputChange);
  }

  @Test
  public void inputChangesCauseRuleKeyChangesForPreprocessing() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
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
                    .build()),
            pathResolver);

    // Generate a rule key for the defaults.

    RuleKey defaultRuleKey = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_PREPROCESSOR,
                DEFAULT_PREPROCESSOR_PLATFORM_FLAGS,
                DEFAULT_PREPROCESOR_RULE_FLAGS,
                DEFAULT_INCLUDE_ROOTS,
                DEFAULT_SYSTEM_INCLUDE_ROOTS,
                DEFAULT_HEADER_MAPS,
                DEFAULT_FRAMEWORK_ROOTS,
                Optional.<SourcePath>absent(),
                DEFAULT_INCLUDES
            ),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));

    // Verify that changing the includes does *not* cause a rulekey change, since we use a
    // different mechanism to track header changes.

    RuleKey includesChange = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_PREPROCESSOR,
                DEFAULT_PREPROCESSOR_PLATFORM_FLAGS,
                DEFAULT_PREPROCESOR_RULE_FLAGS,
                ImmutableSet.of(Paths.get("different")),
                DEFAULT_SYSTEM_INCLUDE_ROOTS,
                DEFAULT_HEADER_MAPS,
                DEFAULT_FRAMEWORK_ROOTS,
                Optional.<SourcePath>absent(),
                DEFAULT_INCLUDES),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertEquals(defaultRuleKey, includesChange);

    // Verify that changing the system includes does *not* cause a rulekey change, since we use a
    // different mechanism to track header changes.

    RuleKey systemIncludesChange = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_PREPROCESSOR,
                DEFAULT_PREPROCESSOR_PLATFORM_FLAGS,
                DEFAULT_PREPROCESOR_RULE_FLAGS,
                DEFAULT_INCLUDE_ROOTS,
                ImmutableSet.of(Paths.get("different")),
                DEFAULT_HEADER_MAPS,
                DEFAULT_FRAMEWORK_ROOTS,
                Optional.<SourcePath>absent(),
                DEFAULT_INCLUDES),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertEquals(defaultRuleKey, systemIncludesChange);

    // Verify that changing the header maps does *not* cause a rulekey change, since we use a
    // different mechanism to track header changes.

    RuleKey headerMapsIncludesChange = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_PREPROCESSOR,
                DEFAULT_PREPROCESSOR_PLATFORM_FLAGS,
                DEFAULT_PREPROCESOR_RULE_FLAGS,
                DEFAULT_INCLUDE_ROOTS,
                DEFAULT_SYSTEM_INCLUDE_ROOTS,
                ImmutableSet.of(Paths.get("different")),
                DEFAULT_FRAMEWORK_ROOTS,
                Optional.<SourcePath>absent(),
                DEFAULT_INCLUDES),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertEquals(defaultRuleKey, headerMapsIncludesChange);

    // Verify that changing the framework roots causes a rulekey change.

    RuleKey frameworkRootsChange = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_PREPROCESSOR,
                DEFAULT_PREPROCESSOR_PLATFORM_FLAGS,
                DEFAULT_PREPROCESOR_RULE_FLAGS,
                DEFAULT_INCLUDE_ROOTS,
                DEFAULT_SYSTEM_INCLUDE_ROOTS,
                DEFAULT_HEADER_MAPS,
                ImmutableSet.of(Paths.get("different")),
                Optional.<SourcePath>absent(),
                DEFAULT_INCLUDES),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, frameworkRootsChange);
  }

  @Test
  public void sanitizedPathsInFlagsDoNotAffectRuleKey() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
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
                    .build()),
            pathResolver);

    // Set up a map to sanitize the differences in the flags.
    int pathSize = 10;
    DebugPathSanitizer sanitizer1 = new DebugPathSanitizer(
        pathSize,
        File.separatorChar,
        Paths.get("PWD"),
        ImmutableBiMap.of(Paths.get("something"), Paths.get("A")));
    DebugPathSanitizer sanitizer2 = new DebugPathSanitizer(
        pathSize,
        File.separatorChar,
        Paths.get("PWD"),
        ImmutableBiMap.of(Paths.get("different"), Paths.get("A")));

    // Generate a rule key for the defaults.
    ImmutableList<String> platformFlags1 = ImmutableList.of("-Isomething/foo");
    ImmutableList<String> ruleFlags1 = ImmutableList.of("-Isomething/bar");

    RuleKey ruleKey1 = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                sanitizer1,
                DEFAULT_PREPROCESSOR,
                platformFlags1,
                ruleFlags1,
                DEFAULT_INCLUDE_ROOTS,
                DEFAULT_SYSTEM_INCLUDE_ROOTS,
                DEFAULT_HEADER_MAPS,
                DEFAULT_FRAMEWORK_ROOTS,
                Optional.<SourcePath>absent(),
                DEFAULT_INCLUDES),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            sanitizer1));

    // Generate a rule key for the defaults.
    ImmutableList<String> platformFlags2 = ImmutableList.of("-Idifferent/foo");
    ImmutableList<String> ruleFlags2 = ImmutableList.of("-Idifferent/bar");

    RuleKey ruleKey2 = ruleKeyBuilderFactory.build(
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                sanitizer2,
                DEFAULT_PREPROCESSOR,
                platformFlags2,
                ruleFlags2,
                DEFAULT_INCLUDE_ROOTS,
                DEFAULT_SYSTEM_INCLUDE_ROOTS,
                DEFAULT_HEADER_MAPS,
                DEFAULT_FRAMEWORK_ROOTS,
                Optional.<SourcePath>absent(),
                DEFAULT_INCLUDES),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            sanitizer2));

    assertEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void usesCorrectCommandForCompile() {

    // Setup some dummy values for inputs to the CxxPreprocessAndCompile.
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    ImmutableList<String> platformFlags = ImmutableList.of("-ffunction-sections");
    ImmutableList<String> ruleFlags = ImmutableList.of("-O3");
    Path output = Paths.get("test.o");
    Path depFile = Paths.get("test.o.dep");
    Path input = Paths.get("test.ii");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            DEFAULT_COMPILER,
            platformFlags,
            ruleFlags,
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
        .add(depFile.toString() + ".tmp")
        .add(input.toString())
        .add("-o", output.toString())
        .build();
    ImmutableList<String> actualCompileCommand = buildRule.makeMainStep().getCommand();
    assertEquals(expectedCompileCommand, actualCompileCommand);
  }

  @Test
  public void usesCorrectCommandForPreprocess() {

    // Setup some dummy values for inputs to the CxxPreprocessAndCompile.
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    ImmutableList<String> platformFlags = ImmutableList.of("-Dtest=blah");
    ImmutableList<String> ruleFlags = ImmutableList.of("-Dfoo=bar");
    Path output = Paths.get("test.ii");
    Path depFile = Paths.get("test.ii.dep");
    Path input = Paths.get("test.cpp");
    Path prefixHeader = Paths.get("prefix.pch");

    CxxPreprocessAndCompile buildRule =
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                DEFAULT_PREPROCESSOR,
                platformFlags,
                ruleFlags,
                ImmutableSet.<Path>of(),
                ImmutableSet.<Path>of(),
                ImmutableSet.<Path>of(),
                DEFAULT_FRAMEWORK_ROOTS,
                Optional.<SourcePath>of(new FakeSourcePath(prefixHeader.toString())),
                ImmutableList.of(CxxHeaders.builder().build())),
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
        .add(prefixHeader.toString())
        .add("-x", "c++")
        .add("-E")
        .add("-MD")
        .add("-MF")
        .add(depFile.toString() + ".tmp")
        .add(input.toString())
        .build();
    ImmutableList<String> actualPreprocessCommand = buildRule.makeMainStep().getCommand();
    assertEquals(expectedPreprocessCommand, actualPreprocessCommand);
  }

  @Test
  public void compilerAndPreprocessorAreAlwaysReturnedFromGetInputsAfterBuildingLocally()
      throws IOException {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();

    Path preprocessor = Paths.get("preprocessor");
    Tool preprocessorTool =
        new CommandTool.Builder()
            .addInput(new PathSourcePath(filesystem, preprocessor))
            .build();

    Path compiler = Paths.get("compiler");
    Tool compilerTool =
        new CommandTool.Builder()
            .addInput(new PathSourcePath(filesystem, compiler))
            .build();

    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();

    CxxPreprocessAndCompile cxxPreprocess =
        CxxPreprocessAndCompile.preprocess(
            params,
            pathResolver,
            new PreprocessorDelegate(
                pathResolver,
                DEFAULT_SANITIZER,
                new DefaultPreprocessor(preprocessorTool),
                ImmutableList.<String>of(),
                ImmutableList.<String>of(),
                DEFAULT_INCLUDE_ROOTS,
                DEFAULT_SYSTEM_INCLUDE_ROOTS,
                DEFAULT_HEADER_MAPS,
                DEFAULT_FRAMEWORK_ROOTS,
                Optional.<SourcePath>absent(),
                DEFAULT_INCLUDES),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER);
    assertThat(
        cxxPreprocess.getInputsAfterBuildingLocally(),
        Matchers.hasItem(preprocessor));

    CxxPreprocessAndCompile cxxCompile =
        CxxPreprocessAndCompile.compile(
            params,
            pathResolver,
            new DefaultCompiler(compilerTool),
            ImmutableList.<String>of(),
            ImmutableList.<String>of(),
            DEFAULT_OUTPUT,
            DEFAULT_INPUT,
            DEFAULT_INPUT_TYPE,
            DEFAULT_SANITIZER);
    assertThat(
        cxxCompile.getInputsAfterBuildingLocally(),
        Matchers.hasItem(compiler));
  }

}
