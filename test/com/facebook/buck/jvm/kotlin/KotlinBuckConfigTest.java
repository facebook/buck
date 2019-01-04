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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.EnvVariablesProvider;
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

  private Path testDataDirectory;

  @Before
  public void setUp() throws IOException {
    KotlinTestAssumptions.assumeUnixLike();

    tmp.newFolder("faux_kotlin_home", "bin");
    tmp.newFolder("faux_kotlin_home", "libexec", "bin");
    tmp.newFolder("faux_kotlin_home", "libexec", "lib");
    tmp.newExecutableFile("faux_kotlin_home/bin/kotlinc");
    tmp.newExecutableFile("faux_kotlin_home/libexec/bin/kotlinc");
    tmp.newExecutableFile("faux_kotlin_home/libexec/lib/kotlin-compiler.jar");
    tmp.newExecutableFile("faux_kotlin_home/libexec/lib/kotlin-stdlib.jar");

    testDataDirectory = tmp.getRoot();
  }

  @Test
  public void testFindsKotlinCompilerInPathLibexec() throws HumanReadableException, IOException {
    // Get faux kotlinc binary location in project
    Path kotlinHome = testDataDirectory.resolve("faux_kotlin_home/libexec/bin").normalize();
    Path kotlinCompiler = kotlinHome.resolve("kotlinc");
    MostFiles.makeExecutable(kotlinCompiler);

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("kotlin", ImmutableMap.of("external", "true")))
            .setEnvironment(
                ImmutableMap.of(
                    "PATH",
                    kotlinHome + pathSeparator + EnvVariablesProvider.getSystemEnv().get("PATH")))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    String command = kotlinBuckConfig.getPathToCompilerBinary().toString();
    assertEquals(command, kotlinCompiler.toString());
  }

  @Test
  public void testFindsKotlinCompilerInPathBin() throws HumanReadableException, IOException {
    // Get faux kotlinc binary location in project
    Path kotlinHome = testDataDirectory.resolve("faux_kotlin_home/bin").normalize();
    Path kotlinCompiler = kotlinHome.resolve("kotlinc");
    MostFiles.makeExecutable(kotlinCompiler);

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("kotlin", ImmutableMap.of("external", "true")))
            .setEnvironment(
                ImmutableMap.of(
                    "PATH",
                    kotlinHome + pathSeparator + EnvVariablesProvider.getSystemEnv().get("PATH")))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    String command = kotlinBuckConfig.getPathToCompilerBinary().toString();
    assertEquals(command, kotlinCompiler.toString());
  }

  @Test
  public void testFindsKotlinCompilerInHomeEnvironment()
      throws HumanReadableException, IOException {
    // Get faux kotlinc binary location in project
    Path kotlinHome = testDataDirectory.resolve("faux_kotlin_home").normalize();
    Path kotlinCompiler = kotlinHome.resolve("bin").resolve("kotlinc");
    MostFiles.makeExecutable(kotlinCompiler);

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("kotlin", ImmutableMap.of("external", "true")))
            .setEnvironment(
                ImmutableMap.of(
                    "KOTLIN_HOME",
                    testDataDirectory.resolve("faux_kotlin_home").toAbsolutePath().toString()))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    String command = kotlinBuckConfig.getPathToCompilerBinary().toString();
    assertEquals(command, kotlinCompiler.toString());
  }

  @Test
  public void testFindsKotlinCompilerInHomeEnvironment2()
      throws HumanReadableException, IOException {
    // Get faux kotlinc binary location in project
    Path kotlinHome = testDataDirectory.resolve("faux_kotlin_home/libexec").normalize();
    Path kotlinCompiler = kotlinHome.resolve("bin").resolve("kotlinc");
    MostFiles.makeExecutable(kotlinCompiler);

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("kotlin", ImmutableMap.of("external", "true")))
            .setEnvironment(
                ImmutableMap.of(
                    "KOTLIN_HOME",
                    testDataDirectory
                        .resolve("faux_kotlin_home/libexec/bin")
                        .toAbsolutePath()
                        .toString()))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    String command = kotlinBuckConfig.getPathToCompilerBinary().toString();
    assertEquals(command, kotlinCompiler.toString());
  }

  @Test
  public void testFindsKotlinCompilerInConfigWithRelativePath()
      throws HumanReadableException, IOException {
    // Get faux kotlinc binary location in project
    Path kotlinHome = testDataDirectory.resolve("faux_kotlin_home").normalize();
    Path kotlinCompiler = kotlinHome.resolve("bin").resolve("kotlinc");
    MostFiles.makeExecutable(kotlinCompiler);

    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(testDataDirectory.resolve("."));
    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setFilesystem(filesystem)
            .setSections(
                ImmutableMap.of(
                    "kotlin",
                    ImmutableMap.of("kotlin_home", "./faux_kotlin_home", "external", "true")))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    String command = kotlinBuckConfig.getPathToCompilerBinary().toString();
    assertEquals(command, kotlinCompiler.toString());
  }

  @Test
  public void testFindsKotlinCompilerJarInConfigWithAbsolutePath() throws HumanReadableException {

    Path kotlinRuntime =
        testDataDirectory
            .resolve("faux_kotlin_home")
            .resolve("libexec")
            .resolve("lib")
            .resolve("kotlin-compiler.jar");

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "kotlin",
                    ImmutableMap.of(
                        "kotlin_home",
                        testDataDirectory.resolve("faux_kotlin_home").toAbsolutePath().toString(),
                        "external",
                        "false")))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    Path compilerJar = kotlinBuckConfig.getPathToCompilerJar();
    assertEquals(kotlinRuntime, compilerJar);
  }

  @Test
  public void testFindsKotlinCompilerJarInConfigWithAbsolutePath2() throws HumanReadableException {

    Path kotlinRuntime =
        testDataDirectory
            .resolve("faux_kotlin_home")
            .resolve("libexec")
            .resolve("lib")
            .resolve("kotlin-compiler.jar");

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "kotlin",
                    ImmutableMap.of(
                        "kotlin_home",
                        testDataDirectory
                            .resolve("faux_kotlin_home")
                            .resolve("libexec")
                            .toAbsolutePath()
                            .toString(),
                        "external",
                        "false")))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    Path compilerJar = kotlinBuckConfig.getPathToCompilerJar();
    assertEquals(kotlinRuntime, compilerJar);
  }

  @Test
  public void testFindsKotlinCompilerLibraryInPath() throws IOException {
    // Get faux kotlinc binary location in project
    Path kotlinHome = testDataDirectory.resolve("faux_kotlin_home").normalize();
    Path kotlinCompiler = kotlinHome.resolve("libexec").resolve("bin").resolve("kotlinc");
    MostFiles.makeExecutable(kotlinCompiler);

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(ImmutableMap.of("kotlin", ImmutableMap.of("external", "true")))
            .setEnvironment(
                ImmutableMap.of(
                    "PATH",
                    kotlinCompiler.getParent()
                        + pathSeparator
                        + EnvVariablesProvider.getSystemEnv().get("PATH")))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    Path compilerJar = kotlinBuckConfig.getPathToCompilerJar();
    Assert.assertThat(
        compilerJar.toString(), Matchers.containsString(testDataDirectory.toString()));
  }

  @Test
  public void testFindsKotlinStdlibJarInConfigWithAbsolutePath() throws HumanReadableException {

    Path kotlinRuntime =
        testDataDirectory
            .resolve("faux_kotlin_home")
            .resolve("libexec")
            .resolve("lib")
            .resolve("kotlin-stdlib.jar");

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "kotlin",
                    ImmutableMap.of(
                        "kotlin_home",
                        testDataDirectory.resolve("faux_kotlin_home").toAbsolutePath().toString(),
                        "external",
                        "false")))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    Path runtimeJar = kotlinBuckConfig.getPathToStdlibJar();
    assertEquals(kotlinRuntime, runtimeJar);
  }

  @Test
  public void testFindsKotlinStdlibJarInConfigWithAbsolutePath2() throws HumanReadableException {

    Path kotlinRuntime =
        testDataDirectory
            .resolve("faux_kotlin_home")
            .resolve("libexec")
            .resolve("lib")
            .resolve("kotlin-stdlib.jar");

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "kotlin",
                    ImmutableMap.of(
                        "kotlin_home",
                        testDataDirectory
                            .resolve("faux_kotlin_home")
                            .resolve("libexec")
                            .resolve("lib")
                            .toAbsolutePath()
                            .toString(),
                        "external",
                        "false")))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    Path runtimeJar = kotlinBuckConfig.getPathToStdlibJar();
    assertEquals(kotlinRuntime, runtimeJar);
  }

  @Test
  public void testFindsKotlinStdlibJarInConfigWithRelativePath() throws HumanReadableException {

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setFilesystem(TestProjectFilesystems.createProjectFilesystem(testDataDirectory))
            .setSections(
                ImmutableMap.of(
                    "kotlin",
                    ImmutableMap.of(
                        "kotlin_home", "faux_kotlin_home",
                        "external", "false")))
            .build();

    KotlinBuckConfig kotlinBuckConfig = new KotlinBuckConfig(buckConfig);
    Path runtimeJar = kotlinBuckConfig.getPathToStdlibJar();
    assertNotNull(runtimeJar);
    assertTrue(runtimeJar.endsWith("faux_kotlin_home/libexec/lib/kotlin-stdlib.jar"));
  }
}
