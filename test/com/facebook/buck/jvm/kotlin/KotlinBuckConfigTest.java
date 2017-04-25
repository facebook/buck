/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.jvm.kotlin;

import static java.io.File.pathSeparator;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class KotlinBuckConfigTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws InterruptedException, IOException {
    KotlinTestAssumptions.assumeUnixLike();

    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "kotlin_compiler_test", tmp);
    workspace.setUp();
  }

  @Test
  public void testFindsKotlinCompilerInPath() throws HumanReadableException, IOException {
    // Get faux kotlinc binary location in project
    Path kotlinPath = workspace.resolve("bin");
    Path kotlinCompiler = kotlinPath.resolve("kotlinc");
    MoreFiles.makeExecutable(kotlinCompiler);

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setEnvironment(
                ImmutableMap.of(
                    "PATH", kotlinPath.toString() + pathSeparator + System.getenv("PATH")))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    String command = kotlinBuckConfig.getKotlinCompiler().get().getCommandPrefix(null).get(0);
    assertEquals(command, kotlinCompiler.toString());
  }

  @Test
  public void testFindsKotlinCompilerInHome() throws HumanReadableException, IOException {
    // Get faux kotlinc binary location in project
    Path kotlinCompiler = workspace.resolve("bin").resolve("kotlinc");
    MoreFiles.makeExecutable(kotlinCompiler);

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setEnvironment(
                ImmutableMap.of("KOTLIN_HOME", workspace.getPath(".").normalize().toString()))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    String command = kotlinBuckConfig.getKotlinCompiler().get().getCommandPrefix(null).get(0);
    assertEquals(command, kotlinCompiler.toString());
  }

  @Test
  public void testFindsKotlinCompilerInConfigWithAbsolutePath()
      throws HumanReadableException, IOException {
    // Get faux kotlinc binary location in project
    Path kotlinCompiler = workspace.resolve("bin").resolve("kotlinc");
    MoreFiles.makeExecutable(kotlinCompiler);

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of("kotlin", ImmutableMap.of("compiler", kotlinCompiler.toString())))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    String command = kotlinBuckConfig.getKotlinCompiler().get().getCommandPrefix(null).get(0);
    assertEquals(command, kotlinCompiler.toString());
  }

  @Test
  public void testFindsKotlinCompilerInConfigWithRelativePath()
      throws HumanReadableException, InterruptedException, IOException {
    // Get faux kotlinc binary location in project
    Path kotlinCompiler = workspace.resolve("bin").resolve("kotlinc");
    MoreFiles.makeExecutable(kotlinCompiler);

    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.resolve("."));
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(ImmutableMap.of("kotlin", ImmutableMap.of("compiler", "bin/kotlinc")))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    String command = kotlinBuckConfig.getKotlinCompiler().get().getCommandPrefix(null).get(0);
    assertEquals(command, kotlinCompiler.toString());
  }

  @Test
  public void testFindsKotlinRuntimeLibraryInPath() throws IOException {
    // Get faux kotlinc binary location in project
    Path kotlinPath = workspace.resolve("bin");
    Path kotlinCompiler = kotlinPath.resolve("kotlinc");
    MoreFiles.makeExecutable(kotlinCompiler);

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setEnvironment(
                ImmutableMap.of(
                    "PATH", kotlinPath.toString() + pathSeparator + System.getenv("PATH")))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    Path runtimeJar = kotlinBuckConfig.getPathToRuntimeJar().getRight();
    Assert.assertThat(
        runtimeJar.toString(),
        Matchers.containsString(workspace.getPath(".").normalize().toString()));
  }

  @Test
  public void testFindsKotlinRuntimeInConfigWithAbsolutePath()
      throws HumanReadableException, IOException {

    Path kotlinRuntime = workspace.resolve("lib").resolve("kotlin-runtime.jar");

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of("kotlin", ImmutableMap.of("runtime_jar", kotlinRuntime.toString())))
            .setEnvironment(
                ImmutableMap.of("KOTLIN_HOME", workspace.getPath(".").normalize().toString()))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    Path runtimeJar = kotlinBuckConfig.getPathToRuntimeJar().getRight();
    assertEquals(runtimeJar.toString(), kotlinRuntime.toString());
  }

  @Test
  public void testFindsKotlinRuntimeInConfigWithRelativePath()
      throws HumanReadableException, InterruptedException, IOException {

    ProjectFilesystem filesystem = new ProjectFilesystem(workspace.resolve("."));
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                ImmutableMap.of("kotlin", ImmutableMap.of("runtime_jar", "lib/kotlin-runtime.jar")))
            .setEnvironment(
                ImmutableMap.of("KOTLIN_HOME", workspace.getPath(".").normalize().toString()))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    PathSourcePath runtimeJar = (PathSourcePath) kotlinBuckConfig.getPathToRuntimeJar().getLeft();
    assertEquals(runtimeJar.getRelativePath().toString(), "lib/kotlin-runtime.jar");
  }
}
