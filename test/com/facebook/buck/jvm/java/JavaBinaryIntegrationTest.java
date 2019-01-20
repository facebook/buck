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

package com.facebook.buck.jvm.java;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.jvm.java.testutil.Bootclasspath;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class JavaBinaryIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void checkPlatform() {
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
  }

  @Test
  public void fatJarLoadingNativeLibraries() throws IOException {
    setUpProjectWorkspaceForScenario("fat_jar");
    workspace.runBuckCommand("run", "//:bin-fat").assertSuccess();
  }

  @Test
  public void fatJarOutputIsRecorded() throws IOException, InterruptedException {
    setUpProjectWorkspaceForScenario("fat_jar");
    workspace.enableDirCache();
    workspace.runBuckCommand("build", "//:bin-fat").assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache");
    Path path = workspace.buildAndReturnOutput("//:bin-fat");
    workspace.getBuildLog().assertTargetWasFetchedFromCache("//:bin-fat");
    assertTrue(workspace.asCell().getFilesystem().exists(path));
  }

  @Test
  public void fatJarWithOutput() throws IOException, InterruptedException {
    setUpProjectWorkspaceForScenario("fat_jar");
    Path jar = workspace.buildAndReturnOutput("//:bin-output");
    ProcessExecutor.Result result = workspace.runJar(jar);
    assertEquals("output", result.getStdout().get().trim());
    assertEquals("error", result.getStderr().get().trim());
  }

  @Test
  public void disableCachingForBinaries() throws IOException {
    setUpProjectWorkspaceForScenario("java_binary_with_blacklist");
    workspace.enableDirCache();
    workspace
        .runBuckBuild("-c", "java.cache_binaries=false", "//:bin-no-blacklist")
        .assertSuccess();
    workspace.runBuckCommand("clean", "--keep-cache").assertSuccess();
    workspace
        .runBuckBuild("-c", "java.cache_binaries=false", "//:bin-no-blacklist")
        .assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally("//:bin-no-blacklist");
  }

  @Test
  public void javaBinaryWithProvidedDeps() throws IOException {
    setUpProjectWorkspaceForScenario("java_binary_with_provided_deps");
    Path binaryJar = workspace.buildAndReturnOutput("//:bin");

    ZipInspector inspector = new ZipInspector(binaryJar);
    inspector.assertFileExists("com/example/buck/Lib.class");
    inspector.assertFileExists("com/example/buck/Dep.class");
    inspector.assertFileExists("com/example/buck/ExportedDep.class");
    inspector.assertFileExists("com/example/buck/DepProvidedDep.class");
    inspector.assertFileDoesNotExist("com/example/buck/ProvidedDep.class");
    inspector.assertFileExists("com/example/buck/ExportedProvidedDep.class");
  }

  @Test
  public void fatJarWithExitCode() throws IOException {
    setUpProjectWorkspaceForScenario("fat_jar");

    workspace
        .runBuckCommand("run", "//:bin-exit-code")
        .assertSpecialExitCode("error", ExitCode.BUILD_ERROR);
  }

  @Test
  public void fatJarWithVmArguments() throws IOException, InterruptedException {
    setUpProjectWorkspaceForScenario("fat_jar");
    ImmutableList<String> args = ImmutableList.of("-ea", "-Dfoo.bar.baz=1234", "-Xms64m");
    String expected = Joiner.on("\n").join(args);
    Path jar = workspace.buildAndReturnOutput("//:bin-jvm-args");
    ProcessExecutor.Result result = workspace.runJar(jar, args);
    assertEquals(expected, result.getStdout().get().trim());
  }

  @Test
  public void fatJarWithAlternateJavaBin() throws IOException, InterruptedException {
    setUpProjectWorkspaceForScenario("fat_jar");
    Path jar = workspace.buildAndReturnOutput("//:bin-alternate-java");
    String javaHomeArg = "-Dbuck.fatjar.java.home=" + tmp.getRoot();
    ProcessExecutor.Result result = workspace.runJar(jar, ImmutableList.of(javaHomeArg));
    assertEquals("Running java wrapper\nRunning inner jar", result.getStdout().get().trim());
  }

  @Test
  public void jarWithMetaInfo() throws IOException {
    setUpProjectWorkspaceForScenario("java_binary_with_meta_inf");
    Path jar = workspace.buildAndReturnOutput("//:bin-meta-inf");
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      assertNotNull(jarFile.getEntry("META-INF/test.txt"));
    }
  }

  @Test
  public void fatJarWithBlacklist() throws IOException {
    setUpProjectWorkspaceForScenario("java_binary_with_blacklist");
    Path binaryJarWithBlacklist = workspace.buildAndReturnOutput("//:bin-blacklist");
    Path binaryJarWithoutBlacklist = workspace.buildAndReturnOutput("//:bin-no-blacklist");

    ImmutableSet<String> commonEntries =
        ImmutableSet.of(
            "META-INF/", "META-INF/MANIFEST.MF", "com/", "com/example/", "com/example/B.class");
    ImmutableSet<String> blacklistedEntries =
        ImmutableSet.of(
            "com/example/A.class",
            "com/example/A$C.class",
            "com/example/Alligator.class",
            "com/example/A.txt");
    assertEquals(
        "com.example.Alligator, com.example.A and any inner classes should be removed.",
        commonEntries,
        new ZipInspector(binaryJarWithBlacklist).getZipFileEntries());
    assertEquals(
        ImmutableSet.builder().addAll(commonEntries).addAll(blacklistedEntries).build(),
        new ZipInspector(binaryJarWithoutBlacklist).getZipFileEntries());
  }

  @Test
  public void testJarWithCorruptInput() throws IOException {
    setUpProjectWorkspaceForScenario("corruption");
    workspace.runBuckBuild("//:simple-lib").assertSuccess();
    String libJar =
        workspace
            .runBuckCommand("targets", "--show_output", "//:simple-lib")
            .assertSuccess()
            .getStdout()
            .split(" ")[1]
            .trim();

    // Now corrupt the output jar.
    Path jarPath = workspace.getPath(libJar);
    byte[] bytes = Files.readAllBytes(jarPath);
    for (int backOffset = 7; backOffset <= 10; backOffset++) {
      bytes[bytes.length - backOffset] = 0x77;
    }
    Files.write(jarPath, bytes);

    ProcessResult result =
        workspace.runBuckBuild("//:wrapper_01").assertExitCode(null, ExitCode.FATAL_IO);
    // Should show the rule that failed.
    assertThat(result.getStderr(), containsString("//:simple-lib"));
    // Should show the jar we were operating on.
    assertThat(result.getStderr(), containsString(libJar));
    // Should show the original exception.
    assertThat(result.getStderr(), containsString("ZipError"));
  }

  @Test
  public void testBootclasspathPathResolution() throws IOException {
    String systemBootclasspath = Bootclasspath.getSystemBootclasspath();
    setUpProjectWorkspaceForScenario("fat_jar");

    ProcessResult result =
        workspace.runBuckBuild(
            "//:bin-output",
            "--config",
            "java.source_level=8",
            "--config",
            "java.target_level=8",
            "--config",
            String.format("java.bootclasspath-8=clowntown.jar:%s", systemBootclasspath),
            "-v",
            "5");
    result.assertSuccess();
    List<String> verboseLogs =
        Splitter.on('\n').trimResults().omitEmptyStrings().splitToList(result.getStderr());
    // Check the javac invocations for properly a resolved bootclasspath and that we aren't
    // accidentally mixing bootclasspaths
    assertThat(
        verboseLogs,
        hasItem(
            allOf(
                containsString("javac"),
                containsString("-bootclasspath"),
                containsString(workspace.getPath("clowntown.jar").toString()))));
  }

  @Test
  public void testExportedProvidedDepsExcludedFromBinary() throws IOException {
    setUpProjectWorkspaceForScenario("exported_provided_deps");
    Path jar = workspace.buildAndReturnOutput("//:binary_without_exported_provided_dep");
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      assertNull(jarFile.getEntry("com/test/ExportedProvidedLibraryClass.class"));
    }
  }

  private ProjectWorkspace setUpProjectWorkspaceForScenario(String scenario) throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmp);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
    return workspace;
  }
}
