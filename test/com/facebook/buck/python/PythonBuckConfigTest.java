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

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cli.FakeBuckEnvironment;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableMap;

import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;

public class PythonBuckConfigTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

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
    assertTrue("Should be able to set file non-executable", configPythonFile.setExecutable(false));
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
                    .build(),
                ImmutableMap.<String, String>of()));
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
                    .build(),
                ImmutableMap.<String, String>of()));
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
                    .build(),
                ImmutableMap.<String, String>of()));
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
                    .build(),
                ImmutableMap.<String, String>of()));
    config.getPythonInterpreter();
    fail("Should throw an exception when Python isn't found.");
  }

  @Test
  public void whenMultiplePythonExecutablesOnPathFirstIsUsed() throws IOException {
    File pythonA = temporaryFolder.newFile("python");
    assertTrue("Should be able to set file executable", pythonA.setExecutable(true));
    DebuggableTemporaryFolder temporaryFolder2 = new DebuggableTemporaryFolder();
    temporaryFolder2.create();
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
                    .build(),
                ImmutableMap.<String, String>of()));
    assertEquals(
        "Should return the first path",
        config.getPythonInterpreter(),
        pythonA.getAbsolutePath());
  }

}
