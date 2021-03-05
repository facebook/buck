/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util.cmd;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class CmdWrapperForScriptWithSpacesTest {

  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Test
  public void supportsOnlyWindows() {
    boolean isWindows = Platform.detect() == Platform.WINDOWS;
    Path binaryPath =
        temporaryFolder
            .getRoot()
            .resolve("foo")
            .resolve("bar")
            .resolve("directory with spaces")
            .resolve("test")
            .getPath();

    if (isWindows) {
      CmdWrapperForScriptWithSpaces cmdWrapperForScriptWithSpaces =
          new CmdWrapperForScriptWithSpaces(binaryPath);
      assertThat(cmdWrapperForScriptWithSpaces, notNullValue());
    } else {
      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class, () -> new CmdWrapperForScriptWithSpaces(binaryPath));
      assertThat(exception.getMessage(), equalTo("Cmd wrapper has to be used only on Windows"));
    }
  }

  @Test
  public void supportsOnlyPathsWithSpaces() {
    boolean isWindows = Platform.detect() == Platform.WINDOWS;
    Path binaryPath =
        temporaryFolder
            .getRoot()
            .resolve("foo")
            .resolve("bar")
            .resolve("directory_without_spaces")
            .resolve("test")
            .getPath();

    if (isWindows) {
      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class, () -> new CmdWrapperForScriptWithSpaces(binaryPath));
      assertThat(
          exception.getMessage(), equalTo("Cmd wrapper has to be used only for paths with spaces"));
    } else {
      IllegalArgumentException exception =
          assertThrows(
              IllegalArgumentException.class, () -> new CmdWrapperForScriptWithSpaces(binaryPath));
      assertThat(exception.getMessage(), equalTo("Cmd wrapper has to be used only on Windows"));
    }
  }

  @Test
  public void wrapBinPathIntoCmdScript() throws IOException {
    Path binaryPath =
        temporaryFolder
            .getRoot()
            .resolve("foo")
            .resolve("bar")
            .resolve("directory with spaces")
            .resolve("test")
            .getPath();

    Optional<Path> cmdPath = CmdWrapperForScriptWithSpaces.wrapPathIntoCmdScript(binaryPath);
    assertThat(cmdPath.isPresent(), is(true));
    String scriptPath = cmdPath.get().toString();
    assertThat(scriptPath, not(containsString(" ")));
    assertThat(scriptPath, endsWith(".cmd"));
    Path path = Paths.get(scriptPath);
    if (Platform.detect() == Platform.WINDOWS) {
      assertThat(Files.isExecutable(path), is(true));
    }

    List<String> lines = Files.readAllLines(path);
    assertThat(lines, hasSize(1));
    assertThat(Iterables.getOnlyElement(lines), equalTo("\"" + binaryPath + "\" %*"));
  }

  @Test
  public void wrapBinaryPathIntoCmdScriptWhereTempDirectoryDoesNotExist() {
    AbsPath tmpDirectory = temporaryFolder.getRoot().resolve("temp");
    Path javaBinPath =
        temporaryFolder
            .getRoot()
            .resolve("foo")
            .resolve("bar")
            .resolve("directory with spaces")
            .resolve("test")
            .getPath();

    Optional<Path> cmdPath =
        CmdWrapperForScriptWithSpaces.wrapPathIntoCmdScript(javaBinPath, tmpDirectory.getPath());
    assertFalse(cmdPath.isPresent());
  }

  @Test
  public void wrapBinaryPathIntoCmdScriptWhereTempDirectoryExistButHasSpaces() throws IOException {
    Path tmpDirectory =
        temporaryFolder.getRoot().resolve("temp").resolve("directory with spaces").getPath();
    Files.createDirectories(tmpDirectory);
    Path javaBinPath =
        temporaryFolder
            .getRoot()
            .resolve("foo")
            .resolve("bar")
            .resolve("directory with spaces")
            .resolve("test")
            .getPath();

    Optional<Path> cmdPath =
        CmdWrapperForScriptWithSpaces.wrapPathIntoCmdScript(javaBinPath, tmpDirectory);
    assertFalse(cmdPath.isPresent());
  }

  @Test
  public void runScript() throws IOException, InterruptedException {
    assumeThat(Platform.detect(), is(Platform.WINDOWS));
    Path binScript = TestDataHelper.getTestDataDirectory(this).resolve("calc.cmd");

    ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());

    // run original script and verify the result
    runAndVerifyScriptResult(
        processExecutor,
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of(binScript.toString(), "123", "789"))
            .build(),
        123 + 789);

    Path binaryPath =
        temporaryFolder
            .getRoot()
            .resolve("foo")
            .resolve("bar")
            .resolve("directory with spaces")
            .resolve("test")
            .resolve("script.cmd")
            .getPath();
    Files.createDirectories(binaryPath.getParent());
    Files.copy(binScript, binaryPath, StandardCopyOption.REPLACE_EXISTING);

    CmdWrapperForScriptWithSpaces cmdWrapperForScriptWithSpaces =
        new CmdWrapperForScriptWithSpaces(binaryPath);
    Optional<Path> cmdPath = cmdWrapperForScriptWithSpaces.getCmdPath();
    assertTrue(cmdPath.isPresent());

    // run wrapper script
    runAndVerifyScriptResult(
        processExecutor,
        ProcessExecutorParams.builder()
            .setCommand(ImmutableList.of(cmdPath.get().toString(), "42", "100500"))
            .build(),
        42 + 100500);
  }

  private void runAndVerifyScriptResult(
      ProcessExecutor processExecutor,
      ProcessExecutorParams processExecutorParams,
      int expectedResult)
      throws InterruptedException, IOException {
    ProcessExecutor.Result result =
        processExecutor.launchAndExecute(
            processExecutorParams,
            ImmutableMap.of(),
            ImmutableSet.<ProcessExecutor.Option>builder()
                .add(ProcessExecutor.Option.EXPECTING_STD_OUT)
                .add(ProcessExecutor.Option.EXPECTING_STD_ERR)
                .build(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    assertThat(result.getExitCode(), is(0));
    Optional<String> stdout = result.getStdout();
    assertTrue(stdout.isPresent());
    String sdtOut = stdout.get().trim();
    String[] lines = sdtOut.split(System.lineSeparator());
    String lastLine = lines[lines.length - 1];
    assertThat(Integer.parseInt(lastLine), is(expectedResult));
  }
}
