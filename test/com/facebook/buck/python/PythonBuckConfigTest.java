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

package com.facebook.buck.python;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.timing.FakeClock;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PythonBuckConfigTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  @Rule
  public DebuggableTemporaryFolder temporaryFolder2 = new DebuggableTemporaryFolder();

  @Test
  public void testGetPythonVersion() throws Exception {
    PythonVersion version =
        PythonBuckConfig.extractPythonVersion(
            Paths.get("usr", "bin", "python"),
            new ProcessExecutor.Result(0, "", "Python 2.7.5\n"));
    assertEquals("Python 2.7", version.toString());
  }

  @Test
  public void whenToolsPythonIsExecutableFileThenItIsUsed() throws IOException {
    File configPythonFile = temporaryFolder.newFile("python");
    assertTrue("Should be able to set file executable", configPythonFile.setExecutable(true));
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of(
                    "python",
                    ImmutableMap.of("interpreter", configPythonFile.getAbsolutePath()))).build(),
            new ExecutableFinder());
    assertEquals(
        "Should return path to temp file.",
        configPythonFile.getAbsolutePath(), config.getPythonInterpreter());
  }

  @Test(expected = HumanReadableException.class)
  public void whenToolsPythonDoesNotExistThenItIsNotUsed() throws IOException {
    String invalidPath = temporaryFolder.getRoot().getAbsolutePath() + "DoesNotExist";
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of("python", ImmutableMap.of("interpreter", invalidPath))).build(),
            new ExecutableFinder());
    config.getPythonInterpreter();
    fail("Should throw exception as python config is invalid.");
  }

  @Test(expected = HumanReadableException.class)
  public void whenToolsPythonIsNonExecutableFileThenItIsNotUsed() throws IOException {
    File configPythonFile = temporaryFolder.newFile("python");
    assumeTrue("Should be able to set file non-executable", configPythonFile.setExecutable(false));
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of(
                    "python",
                    ImmutableMap.of("interpreter", configPythonFile.getAbsolutePath()))).build(),
            new ExecutableFinder());
    config.getPythonInterpreter();
    fail("Should throw exception as python config is invalid.");
  }

  @Test(expected = HumanReadableException.class)
  public void whenToolsPythonIsExecutableDirectoryThenItIsNotUsed() throws IOException {
    File configPythonFile = temporaryFolder.newFolder("python");
    assertTrue("Should be able to set file executable", configPythonFile.setExecutable(true));
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of(
                    "python",
                    ImmutableMap.of("interpreter", configPythonFile.getAbsolutePath()))).build(),
            new ExecutableFinder());
    config.getPythonInterpreter();
    fail("Should throw exception as python config is invalid.");
  }

  @Test
  public void whenPythonOnPathIsExecutableFileThenItIsUsed() throws IOException {
    File python = temporaryFolder.newFile("python");
    assertTrue("Should be able to set file executable", python.setExecutable(true));
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setEnvironment(
                ImmutableMap.<String, String>builder()
                    .put("PATH", temporaryFolder.getRoot().getAbsolutePath())
                    .put("PATHEXT", "")
                    .build()).build(),
            new ExecutableFinder());
    config.getPythonInterpreter();
  }

  @Test
  public void whenPythonPlusExtensionOnPathIsExecutableFileThenItIsUsed() throws IOException {
    File python = temporaryFolder.newFile("my-py.exe");
    assertTrue("Should be able to set file executable", python.setExecutable(true));
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setSections(ImmutableMap.of("python", ImmutableMap.of("interpreter", "my-py")))
                .setEnvironment(
                    ImmutableMap.<String, String>builder()
                        .put("PATH", temporaryFolder.getRoot().getAbsolutePath())
                        .put("PATHEXT", ".exe")
                        .build())
                .build(),
            new ExecutableFinder(Platform.WINDOWS));
    config.getPythonInterpreter();
  }

  @Test
  public void whenPython2OnPathThenItIsUsed() throws IOException {
    File python = temporaryFolder.newFile("python");
    assertTrue("Should be able to set file executable", python.setExecutable(true));
    File python2 = temporaryFolder.newFile("python2");
    assertTrue("Should be able to set file executable", python2.setExecutable(true));
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setEnvironment(
                ImmutableMap.<String, String>builder()
                    .put("PATH", temporaryFolder.getRoot().getAbsolutePath())
                    .put("PATHEXT", "")
                    .build()).build(),
            new ExecutableFinder());
    assertEquals(
        "Should return path to python2.",
        python2.getAbsolutePath(),
        config.getPythonInterpreter());
  }

  @Test(expected = HumanReadableException.class)
  public void whenPythonOnPathNotFoundThenThrow() throws IOException {
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "python", ImmutableMap.of("interpreter", "does-not-exist")))
                .setEnvironment(
                    ImmutableMap.<String, String>builder()
                        .put("PATH", temporaryFolder.getRoot().getAbsolutePath())
                        .put("PATHEXT", "")
                        .build())
                .build(),
            new ExecutableFinder());
    config.getPythonInterpreter();
    fail("Should throw an exception when Python isn't found.");
  }

  @Test
  public void whenMultiplePythonExecutablesOnPathFirstIsUsed() throws IOException {
    File pythonA = temporaryFolder.newFile("python2");
    assertTrue("Should be able to set file executable", pythonA.setExecutable(true));
    File pythonB = temporaryFolder2.newFile("python2");
    assertTrue("Should be able to set file executable", pythonB.setExecutable(true));
    String path = temporaryFolder.getRoot().getAbsolutePath() +
        File.pathSeparator +
        temporaryFolder2.getRoot().getAbsolutePath();
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setEnvironment(
                ImmutableMap.<String, String>builder()
                    .put("PATH", path)
                    .put("PATHEXT", "")
                    .build()).build(),
            new ExecutableFinder());
    assertEquals(
        "Should return the first path",
        config.getPythonInterpreter(),
        pythonA.getAbsolutePath());
  }

  @Test
  public void testPathToPexExecuterUsesConfigSetting() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    DebuggableTemporaryFolder projectDir = new DebuggableTemporaryFolder();
    projectDir.create();
    Path pexExecuter = Paths.get("pex-exectuter");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(
        new FakeClock(0),
        projectDir.getRoot(),
        ImmutableSet.of(pexExecuter));
    Files.createFile(projectFilesystem.resolve(pexExecuter));
    assertTrue(
        "Should be able to set file executable",
        projectFilesystem.resolve(pexExecuter).toFile().setExecutable(true));
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "python",
                        ImmutableMap.of(
                            "path_to_pex_executer",
                            pexExecuter.toString())))
                .setFilesystem(projectFilesystem)
                .build(),
            new ExecutableFinder());
    assertThat(
        config.getPathToPexExecuter(resolver).get().getCommandPrefix(pathResolver),
        Matchers.contains(pexExecuter.toString()));
  }

  @Test
  public void testGetPyrunVersion() throws Exception {
    PythonVersion version =
        PythonBuckConfig.extractPythonVersion(
            Paths.get("non", "important", "path"),
            new ProcessExecutor.Result(0, "", "pyrun 2.7.6 (release 2.0.0)\n"));
    assertEquals("pyrun 2.7", version.toString());
  }

  @Test
  public void testGetPypyVersion() throws Exception {
    String pypyOutput =
        "Python 2.7.10 (850edf14b2c75573720f59e95767335fb1affe55, Oct 30 2015, 00:18:28)\n" +
        "[PyPy 4.0.0 with GCC 4.2.1 Compatible Apple LLVM 7.0.0 (clang-700.1.76)]\n";

    PythonVersion version =
        PythonBuckConfig.extractPythonVersion(
            Paths.get("non", "important", "path"),
            new ProcessExecutor.Result(0, "", pypyOutput));
    assertEquals("Python 2.7", version.toString());
  }

  @Test
  public void testGetPypyWindowsVersion() throws Exception {
    String pypyOutput =
        "Python 2.7.10 (850edf14b2c75573720f59e95767335fb1affe55, Oct 30 2015, 00:18:28)\r\n" +
        "[PyPy 4.0.0 with GCC 4.2.1 Compatible Apple LLVM 7.0.0 (ok that was a lie)]\r\n";

    PythonVersion version =
        PythonBuckConfig.extractPythonVersion(
            Paths.get("non", "important", "path"),
            new ProcessExecutor.Result(0, "", pypyOutput));
    assertEquals("Python 2.7", version.toString());
  }

  @Test
  public void testDefaultPythonLibrary() throws InterruptedException {
    BuildTarget library = BuildTargetFactory.newInstance("//:library");
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of("python", ImmutableMap.of("library", library.toString()))).build(),
            new AlwaysFoundExecutableFinder());
    assertThat(
        config.getDefaultPythonPlatform(
            new FakeProcessExecutor(
                Functions.constant(new FakeProcess(0, "Python 2.7.5", "")),
                new TestConsole()))
            .getCxxLibrary(),
        Matchers.equalTo(Optional.of(library)));
  }

}
