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

import static com.facebook.buck.testutil.HasConsecutiveItemsMatcher.hasConsecutiveItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.AlwaysFoundExecutableFinder;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.timing.FakeClock;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
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
import java.util.Optional;

public class PythonBuckConfigTest {

  @Rule
  public TemporaryPaths temporaryFolder = new TemporaryPaths();

  @Rule
  public TemporaryPaths temporaryFolder2 = new TemporaryPaths();

  @Test
  public void testGetBuildConfig() throws Exception {
    PythonBuildConfigAndVersion actual =
        PythonBuckConfig.extractPythonBuildConfigAndVersion(
            PythonTestUtils.CPYTHON_3_5_DISCOVER_OUTPUT);
    PythonBuildConfigAndVersion expected = PythonBuildConfigAndVersion.of(
        PythonVersion.of("CPython", "3.5"),
        PythonBuildConfig.of(
            ImmutableList.of("-I/usr/local/include/python3.5m", "-I/usr/local/include/python3.5m"),
            ImmutableList.of(
                "-I/usr/local/include/python3.5m",
                "-I/usr/local/include/python3.5m",
                "-Wno-unused-result",
                "-Wsign-compare",
                "-DNDEBUG",
                "-g",
                "-fwrapv",
                "-O3",
                "-Wall",
                "-Wstrict-prototypes"),
            ImmutableList.of(
                "-L/usr/local/lib/python3.5/config-3.5m",
                "-lpython3.5",
                "-lpthread",
                "-ldl",
                "-lutil",
                "-lm",
                "-Xlinker",
                "-export-dynamic"),
            "so"
        ));
    assertEquals(expected, actual);
  }

  @Test
  public void whenToolsPythonIsExecutableFileThenItIsUsed() throws IOException {
    Path configPythonFile = temporaryFolder.newExecutableFile("python");
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "python",
                        ImmutableMap.of(
                            "interpreter",
                            configPythonFile.toAbsolutePath().toString())))
                .build(),
            new ExecutableFinder());
    assertEquals(
        "Should return path to temp file.",
        configPythonFile.toAbsolutePath().toString(),
        config.getPythonInterpreter());
  }

  @Test(expected = HumanReadableException.class)
  public void whenToolsPythonDoesNotExistThenItIsNotUsed() throws IOException {
    String invalidPath = temporaryFolder.getRoot().toAbsolutePath() + "DoesNotExist";
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
    assumeThat(
        "On windows all files are executable.",
        Platform.detect(),
        is(not(Platform.WINDOWS)));
    Path configPythonFile = temporaryFolder.newFile("python");
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "python",
                        ImmutableMap.of(
                            "interpreter",
                            configPythonFile.toAbsolutePath().toString())))
                .build(),
            new ExecutableFinder());
    config.getPythonInterpreter();
    fail("Should throw exception as python config is invalid.");
  }

  @Test(expected = HumanReadableException.class)
  public void whenToolsPythonIsExecutableDirectoryThenItIsNotUsed() throws IOException {
    Path configPythonFile = temporaryFolder.newFolder("python");
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "python",
                        ImmutableMap.of(
                            "interpreter",
                            configPythonFile.toAbsolutePath().toString())))
                .build(),
            new ExecutableFinder());
    config.getPythonInterpreter();
    fail("Should throw exception as python config is invalid.");
  }

  @Test
  public void whenPythonOnPathIsExecutableFileThenItIsUsed() throws IOException {
    temporaryFolder.newExecutableFile("python");
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setEnvironment(
                ImmutableMap.<String, String>builder()
                    .put("PATH", temporaryFolder.getRoot().toAbsolutePath().toString())
                    .put("PATHEXT", "")
                    .build()).build(),
            new ExecutableFinder());
    config.getPythonInterpreter();
  }

  @Test
  public void whenPythonPlusExtensionOnPathIsExecutableFileThenItIsUsed() throws IOException {
    temporaryFolder.newExecutableFile("my-py.exe");
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder()
                .setSections(ImmutableMap.of("python", ImmutableMap.of("interpreter", "my-py")))
                .setEnvironment(
                    ImmutableMap.<String, String>builder()
                        .put("PATH", temporaryFolder.getRoot().toAbsolutePath().toString())
                        .put("PATHEXT", ".exe")
                        .build())
                .build(),
            new ExecutableFinder(Platform.WINDOWS));
    config.getPythonInterpreter();
  }

  @Test
  public void whenPython2OnPathThenItIsUsed() throws IOException {
    temporaryFolder.newExecutableFile("python");
    Path python2 = temporaryFolder.newExecutableFile("python2");
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setEnvironment(
                ImmutableMap.<String, String>builder()
                    .put("PATH", temporaryFolder.getRoot().toAbsolutePath().toString())
                    .put("PATHEXT", "")
                    .build()).build(),
            new ExecutableFinder());
    assertEquals(
        "Should return path to python2.",
        python2.toAbsolutePath().toString(),
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
                        .put("PATH", temporaryFolder.getRoot().toAbsolutePath().toString())
                        .put("PATHEXT", "")
                        .build())
                .build(),
            new ExecutableFinder());
    config.getPythonInterpreter();
    fail("Should throw an exception when Python isn't found.");
  }

  @Test
  public void whenMultiplePythonExecutablesOnPathFirstIsUsed() throws IOException {
    Path pythonA = temporaryFolder.newExecutableFile("python2");
    temporaryFolder2.newExecutableFile("python2");
    String path = temporaryFolder.getRoot().toAbsolutePath() +
        File.pathSeparator +
        temporaryFolder2.getRoot().toAbsolutePath();
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
        pythonA.toAbsolutePath().toString());
  }

  @Test
  public void testPathToPexExecuterUsesConfigSetting() throws IOException {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    Path projectDir = Files.createTempDirectory("project");
    Path pexExecuter = Paths.get("pex-exectuter");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(
        new FakeClock(0),
        projectDir,
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
        config.getPexExecutor(resolver).get().getCommandPrefix(pathResolver),
        Matchers.contains(pexExecuter.toString()));
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
                Functions.constant(
                    new FakeProcess(
                        0,
                        PythonTestUtils.CPYTHON_3_5_DISCOVER_OUTPUT,
                        "")),
                new TestConsole()))
            .getCxxLibrary(),
        Matchers.equalTo(Optional.of(library)));
  }

  @Test
  public void testPexArgs() throws Exception {
    PythonBuckConfig config =
        new PythonBuckConfig(
            FakeBuckConfig.builder().setSections(
                ImmutableMap.of("python", ImmutableMap.of("pex_flags", "--hello --world"))).build(),
            new AlwaysFoundExecutableFinder());
    BuildRuleResolver resolver = new BuildRuleResolver(
        TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    assertThat(
        config.getPexTool(resolver).getCommandPrefix(
            new SourcePathResolver(new SourcePathRuleFinder(resolver))),
        hasConsecutiveItems("--hello", "--world"));
  }
}
