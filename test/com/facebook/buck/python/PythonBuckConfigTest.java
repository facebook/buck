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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cli.FakeBuckEnvironment;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.timing.FakeClock;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

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
            new FakeBuckConfig(
                ImmutableMap.<String, Map<String, String>>builder()
                    .put(
                        "python",
                        ImmutableMap.of("interpreter", configPythonFile.getAbsolutePath()))
                    .build()));
    assertEquals(
        "Should return path to temp file.",
        configPythonFile.getAbsolutePath(), config.getPythonInterpreter());
  }

  @Test(expected = HumanReadableException.class)
  public void whenToolsPythonDoesNotExistThenItIsNotUsed() throws IOException {
    String invalidPath = temporaryFolder.getRoot().getAbsolutePath() + "DoesNotExist";
    PythonBuckConfig config =
        new PythonBuckConfig(
            new FakeBuckConfig(
                ImmutableMap.<String, Map<String, String>>builder()
                    .put("python", ImmutableMap.of("interpreter", invalidPath))
                    .build()));
    config.getPythonInterpreter();
    fail("Should throw exception as python config is invalid.");
  }

  @Test(expected = HumanReadableException.class)
  public void whenToolsPythonIsNonExecutableFileThenItIsNotUsed() throws IOException {
    File configPythonFile = temporaryFolder.newFile("python");
    assumeTrue("Should be able to set file non-executable", configPythonFile.setExecutable(false));
    PythonBuckConfig config =
        new PythonBuckConfig(
            new FakeBuckConfig(
                ImmutableMap.<String, Map<String, String>>builder()
                    .put(
                        "python",
                        ImmutableMap.of("interpreter", configPythonFile.getAbsolutePath()))
                    .build()));
    config.getPythonInterpreter();
    fail("Should throw exception as python config is invalid.");
  }

  @Test(expected = HumanReadableException.class)
  public void whenToolsPythonIsExecutableDirectoryThenItIsNotUsed() throws IOException {
    File configPythonFile = temporaryFolder.newFolder("python");
    assertTrue("Should be able to set file executable", configPythonFile.setExecutable(true));
    PythonBuckConfig config =
        new PythonBuckConfig(
            new FakeBuckConfig(
                ImmutableMap.<String, Map<String, String>>builder()
                    .put(
                        "python",
                        ImmutableMap.of("interpreter", configPythonFile.getAbsolutePath()))
                    .build()));
    config.getPythonInterpreter();
    fail("Should throw exception as python config is invalid.");
  }

  @Test
  public void whenPythonOnPathIsExecutableFileThenItIsUsed() throws IOException {
    File python = temporaryFolder.newFile("python");
    assertTrue("Should be able to set file executable", python.setExecutable(true));
    PythonBuckConfig config =
        new PythonBuckConfig(
            new FakeBuckEnvironment(
                ImmutableMap.<String, Map<String, String>>of(),
                ImmutableMap.<String, String>builder()
                    .put("PATH", temporaryFolder.getRoot().getAbsolutePath())
                    .put("PATHEXT", "")
                    .build()));
    config.getPythonInterpreter();
  }

  @Test
  public void whenPythonPlusExtensionOnPathIsExecutableFileThenItIsUsed() throws IOException {
    File python = temporaryFolder.newFile("python.exe");
    assertTrue("Should be able to set file executable", python.setExecutable(true));
    PythonBuckConfig config =
        new PythonBuckConfig(
            new FakeBuckEnvironment(
                ImmutableMap.<String, Map<String, String>>of(),
                ImmutableMap.<String, String>builder()
                    .put("PATH", temporaryFolder.getRoot().getAbsolutePath())
                    .put("PATHEXT", ".exe")
                    .build()));
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
            new FakeBuckEnvironment(
                ImmutableMap.<String, Map<String, String>>of(),
                ImmutableMap.<String, String>builder()
                    .put("PATH", temporaryFolder.getRoot().getAbsolutePath())
                    .put("PATHEXT", "")
                    .build()));
    assertEquals(
        "Should return path to python2.",
        python2.getAbsolutePath(),
        config.getPythonInterpreter());
  }

  @Test(expected = HumanReadableException.class)
  public void whenPythonOnPathNotFoundThenThrow() throws IOException {
    PythonBuckConfig config =
        new PythonBuckConfig(
            new FakeBuckEnvironment(
                ImmutableMap.<String, Map<String, String>>of(),
                ImmutableMap.<String, String>builder()
                    .put("PATH", temporaryFolder.getRoot().getAbsolutePath())
                    .put("PATHEXT", "")
                    .build()));
    config.getPythonInterpreter();
    fail("Should throw an exception when Python isn't found.");
  }

  @Test
  public void whenMultiplePythonExecutablesOnPathFirstIsUsed() throws IOException {
    File pythonA = temporaryFolder.newFile("python");
    assertTrue("Should be able to set file executable", pythonA.setExecutable(true));
    File pythonB = temporaryFolder2.newFile("python");
    assertTrue("Should be able to set file executable", pythonB.setExecutable(true));
    String path = temporaryFolder.getRoot().getAbsolutePath() +
        File.pathSeparator +
        temporaryFolder2.getRoot().getAbsolutePath();
    PythonBuckConfig config =
        new PythonBuckConfig(
            new FakeBuckEnvironment(
                ImmutableMap.<String, Map<String, String>>of(),
                ImmutableMap.<String, String>builder()
                    .put("PATH", path)
                    .put("PATHEXT", "")
                    .build()));
    assertEquals(
        "Should return the first path",
        config.getPythonInterpreter(),
        pythonA.getAbsolutePath());
  }

  @Test
  public void testPathToPexExecuterDefaultsToPython() throws IOException {
    File python2 = temporaryFolder.newFile("python2");
    assertTrue("Should be able to set file executable", python2.setExecutable(true));
    PythonBuckConfig config =
        new PythonBuckConfig(
            new FakeBuckEnvironment(
                ImmutableMap.<String, Map<String, String>>of(),
                ImmutableMap.<String, String>builder()
                    .put("PATH", temporaryFolder.getRoot().getAbsolutePath())
                    .put("PATHEXT", "")
                    .build()));
    assertEquals(config.getPathToPexExecuter().toString(), config.getPythonInterpreter());
  }

  @Test
  public void testPathToPexExecuterUsesConfigSetting() throws IOException {
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
            new FakeBuckConfig(
                ImmutableMap.<String, Map<String, String>>builder()
                    .put(
                        "python",
                        ImmutableMap.of(
                            "path_to_pex_executer",
                            pexExecuter.toString())).build(),
                projectFilesystem));
    assertEquals(config.getPathToPexExecuter(), projectFilesystem.resolve(pexExecuter));
  }

  @Test(expected = HumanReadableException.class)
  public void testPathToPexExecuterNotExecutableThrows() throws IOException {
    DebuggableTemporaryFolder projectDir = new DebuggableTemporaryFolder();
    projectDir.create();
    Path pexExecuter = Paths.get("pex-executer");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(
        new FakeClock(0),
        projectDir.getRoot(),
        ImmutableSet.of(pexExecuter));
    Files.createFile(projectFilesystem.resolve(pexExecuter));
    assumeTrue(
        "Should be able to set file non-executable",
        projectFilesystem.resolve(pexExecuter).toFile().setExecutable(false));
    PythonBuckConfig config =
        new PythonBuckConfig(
            new FakeBuckConfig(
                ImmutableMap.<String, Map<String, String>>builder()
                    .put(
                        "python",
                        ImmutableMap.of(
                            "path_to_pex_executer",
                            pexExecuter.toString())).build(),
                projectFilesystem));
    config.getPathToPexExecuter();
  }
}
