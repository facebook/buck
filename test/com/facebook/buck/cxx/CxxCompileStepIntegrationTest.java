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

package com.facebook.buck.cxx;

import static com.facebook.buck.file.ProjectFilesystemMatchers.pathExists;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.cxx.toolchain.Compiler;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.MungingDebugPathSanitizer;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

public class CxxCompileStepIntegrationTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private void assertCompDir(Path compDir, Optional<String> failure) throws Exception {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    CxxPlatform platform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    // Build up the paths to various files the archive step will use.
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    Compiler compiler = platform.getCc().resolve(resolver);
    ImmutableList<String> compilerCommandPrefix = compiler.getCommandPrefix(pathResolver);
    Path output = filesystem.resolve(Paths.get("output.o"));
    Path depFile = filesystem.resolve(Paths.get("output.dep"));
    Path relativeInput = Paths.get("input.c");
    Path input = filesystem.resolve(relativeInput);
    filesystem.writeContentsToPath("int main() {}", relativeInput);
    Path scratchDir = filesystem.getPath("scratchDir");
    filesystem.mkdirs(scratchDir);

    ImmutableList.Builder<String> compilerArguments = ImmutableList.builder();
    compilerArguments.add("-g");

    DebugPathSanitizer sanitizer =
        new MungingDebugPathSanitizer(200, File.separatorChar, compDir, ImmutableBiMap.of());

    // Build an archive step.
    CxxPreprocessAndCompileStep step =
        new CxxPreprocessAndCompileStep(
            filesystem,
            CxxPreprocessAndCompileStep.Operation.PREPROCESS_AND_COMPILE,
            output,
            Optional.of(depFile),
            relativeInput,
            CxxSource.Type.C,
            new CxxPreprocessAndCompileStep.ToolCommand(
                compilerCommandPrefix, compilerArguments.build(), ImmutableMap.of()),
            HeaderPathNormalizer.empty(pathResolver),
            sanitizer,
            scratchDir,
            true,
            compiler,
            Optional.empty());

    // Execute the archive step and verify it ran successfully.
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    TestConsole console = (TestConsole) executionContext.getConsole();
    int exitCode = step.execute(executionContext).getExitCode();
    if (failure.isPresent()) {
      assertNotEquals("compile step succeeded", 0, exitCode);
      assertThat(
          console.getTextWrittenToStdErr(),
          console.getTextWrittenToStdErr(),
          Matchers.containsString(failure.get()));
    } else {
      assertEquals("compile step failed: " + console.getTextWrittenToStdErr(), 0, exitCode);
      // Verify that we find the expected compilation dir embedded in the file.
      String contents = new String(Files.readAllBytes(output));
      assertThat(contents, Matchers.containsString(sanitizer.getCompilationDirectory()));
    }

    // Cleanup.
    Files.delete(input);
    Files.deleteIfExists(output);
  }

  @Test
  public void updateCompilationDir() throws Exception {
    assertCompDir(Paths.get("."), Optional.empty());
    assertCompDir(Paths.get("blah"), Optional.empty());
  }

  @Test
  public void createsAnArgfile() throws Exception {
    ProjectFilesystem filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    CxxPlatform platform =
        CxxPlatformUtils.build(new CxxBuckConfig(FakeBuckConfig.builder().build()));

    // Build up the paths to various files the archive step will use.
    BuildRuleResolver resolver = new TestBuildRuleResolver();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));
    Compiler compiler = platform.getCc().resolve(resolver);
    ImmutableList<String> compilerCommandPrefix = compiler.getCommandPrefix(pathResolver);
    Path output = filesystem.resolve(Paths.get("output.o"));
    Path depFile = filesystem.resolve(Paths.get("output.dep"));
    Path relativeInput = Paths.get("input.c");
    Path input = filesystem.resolve(relativeInput);
    filesystem.writeContentsToPath("int main() {}", relativeInput);
    Path scratchDir = filesystem.getPath("scratchDir");
    filesystem.mkdirs(scratchDir);

    ImmutableList.Builder<String> compilerArguments = ImmutableList.builder();
    compilerArguments.add("-g");

    // Build an archive step.
    CxxPreprocessAndCompileStep step =
        new CxxPreprocessAndCompileStep(
            filesystem,
            CxxPreprocessAndCompileStep.Operation.PREPROCESS_AND_COMPILE,
            output,
            Optional.of(depFile),
            relativeInput,
            CxxSource.Type.C,
            new CxxPreprocessAndCompileStep.ToolCommand(
                compilerCommandPrefix, compilerArguments.build(), ImmutableMap.of()),
            HeaderPathNormalizer.empty(pathResolver),
            CxxPlatformUtils.DEFAULT_COMPILER_DEBUG_PATH_SANITIZER,
            scratchDir,
            true,
            compiler,
            Optional.empty());

    // Execute the archive step and verify it ran successfully.
    ExecutionContext executionContext = TestExecutionContext.newInstance();
    TestConsole console = (TestConsole) executionContext.getConsole();
    int exitCode = step.execute(executionContext).getExitCode();
    assertEquals("compile step failed: " + console.getTextWrittenToStdErr(), 0, exitCode);

    Path argfile = filesystem.resolve(scratchDir.resolve("ppandcompile.argsfile"));
    assertThat(filesystem, pathExists(argfile));
    assertThat(Files.readAllLines(argfile, StandardCharsets.UTF_8), hasItem(equalTo("-g")));

    // Cleanup.
    Files.delete(input);
    Files.deleteIfExists(output);
  }
}
