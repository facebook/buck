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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxCompileStepIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  private void assertCompDir(Path compDir, Optional<String> failure) throws Exception {
    ProjectFilesystem filesystem = new ProjectFilesystem(tmp.getRoot().toPath());
    CxxPlatform platform = DefaultCxxPlatforms.build(new CxxBuckConfig(new FakeBuckConfig()));

    // Build up the paths to various files the archive step will use.
    ImmutableList<String> compiler =
        platform.getCc().getCommandPrefix(new SourcePathResolver(new BuildRuleResolver()));
    Path output = filesystem.resolve(Paths.get("output.o"));
    Path relativeInput = Paths.get("input.c");
    Path input = filesystem.resolve(relativeInput);
    filesystem.writeContentsToPath("int main() {}", relativeInput);

    ImmutableList.Builder<String> cmd = ImmutableList.builder();
    cmd.addAll(compiler);
    cmd.add(CxxPreprocessAndCompileStep.Operation.COMPILE.getFlag());
    cmd.add("-g");
    cmd.add("-o", output.toString());
    cmd.add(relativeInput.toString());

    DebugPathSanitizer sanitizer = new DebugPathSanitizer(
        200,
        File.separatorChar,
        compDir,
        ImmutableBiMap.<Path, Path>of());

    // Build an archive step.
    CxxPreprocessAndCompileStep step = new CxxPreprocessAndCompileStep(
        CxxPreprocessAndCompileStep.Operation.COMPILE,
        output,
        relativeInput,
        cmd.build(),
        ImmutableMap.<Path, Path>of(),
        Optional.of(sanitizer));

    // Execute the archive step and verify it ran successfully.
    ExecutionContext executionContext =
        TestExecutionContext.newBuilder()
            .setProjectFilesystem(new ProjectFilesystem(tmp.getRoot().toPath()))
            .build();
    TestConsole console = (TestConsole) executionContext.getConsole();
    int exitCode = step.execute(executionContext);
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
      assertThat(
          contents,
          Matchers.containsString(sanitizer.getCompilationDirectory()));
    }

    // Cleanup.
    Files.delete(input);
    Files.deleteIfExists(output);
  }

  @Test
  public void updateCompilationDir() throws Exception {
    assertCompDir(Paths.get("."), Optional.<String>absent());
    assertCompDir(Paths.get("blah"), Optional.<String>absent());
  }

}
