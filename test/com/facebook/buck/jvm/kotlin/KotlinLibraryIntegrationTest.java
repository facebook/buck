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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MostFiles;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class KotlinLibraryIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException, InterruptedException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "kotlin_library_description", tmp);
    workspace.setUp();

    Path kotlincPath = TestDataHelper.getTestDataScenario(this, "kotlinc");
    MostFiles.copyRecursively(kotlincPath, tmp.newFolder("kotlinc"));

    KotlinTestAssumptions.assumeCompilerAvailable(workspace.asCell().getBuckConfig());
  }

  @Test
  public void shouldCompileKotlinClass() throws Exception {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/good:example");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldCompileKotlinClassWithExternalJavac() throws Exception {
    overrideToolsJavacInBuckConfig();

    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/good:example");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void compileKotlinClassWithAnnotationProcessorThatGeneratesJavaCode() throws Exception {
    buildKotlinLibraryThatContainsNoJavaCodeButMustCompileGeneratedJavaCode();
  }

  @Test
  public void compileKotlinClassWithAnnotationProcessorThatGeneratesJavaCodeWithExternalJavac()
      throws Exception {
    overrideToolsJavacInBuckConfig();
    buildKotlinLibraryThatContainsNoJavaCodeButMustCompileGeneratedJavaCode();
  }

  private void buildKotlinLibraryThatContainsNoJavaCodeButMustCompileGeneratedJavaCode()
      throws IOException {
    Path jarFile = workspace.buildAndReturnOutput("//com/example/ap/kotlinapgenjava:example");
    assertTrue(jarFile.getFileName().toString().endsWith(".jar"));

    try (JarFile jf = new JarFile(jarFile.toString())) {
      Set<String> entries =
          jf.stream().map(Functions.toStringFunction()).collect(Collectors.toSet());
      assertEquals(
          ImmutableSet.of(
              "META-INF/",
              "META-INF/MANIFEST.MF",
              "META-INF/main.kotlin_module",
              "com/",
              "com/example/",
              "com/example/ap/",
              "com/example/ap/kotlinapgenjava/",
              "com/example/ap/kotlinapgenjava/Example.class",
              "com/example/ap/kotlinapgenjava/Example_.class"),
          entries);
    }
  }

  @Test
  public void pureTreeOfKotlinCodeWithAnnotationProcessorsShouldNotMakeAnInvalidJavacCall()
      throws Exception {
    overrideToolsJavacInBuckConfig();
    Path jarFile = workspace.buildAndReturnOutput("//com/example/ap/purekotlinap:example");
    assertNotNull(
        new JarFile(jarFile.toString()).getEntry("com/example/ap/purekotlinap/Example_.class"));
  }

  private void overrideToolsJavacInBuckConfig() throws IOException {
    ExecutableFinder exeFinder = new ExecutableFinder();
    Optional<Path> javac =
        exeFinder.getOptionalExecutable(Paths.get("javac"), EnvVariablesProvider.getSystemEnv());
    Assume.assumeTrue(javac.isPresent());

    // If javac is found on the $PATH, add a section to .buckconfig to override tools.javac.
    // This is a regression test to ensure kotlin_library() works with an external javac; in
    // particular, when there are no .java files in the srcs of the rule.
    String buckconfig = workspace.getFileContents(".buckconfig");
    String amendedBuckconfig = buckconfig + String.format("[tools]\njavac = %s", javac.get());
    workspace.writeContentsToPath(amendedBuckconfig, ".buckconfig");
  }

  @Test(timeout = 100000)
  public void shouldAnnotationProcessClassesUsingKapt() throws Exception {
    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//com/example/ap/annotation-processing-tool-kapt:kotlin");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldAnnotationProcessClassesUsingJavac() throws Exception {
    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//com/example/ap/annotation-processing-tool-javac:kotlin");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldCompileLibraryWithDependencyOnAnother() throws Exception {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/child:child");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldFailToCompileInvalidKotlinCode() throws Exception {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/bad:fail");
    buildResult.assertFailure();
  }

  @Test
  public void shouldCompileMixedJavaAndKotlinSources() throws Exception {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/mixed:example");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldCompileKotlinSrcZip() throws Exception {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/zip:zip");
    buildResult.assertSuccess("Build should have succeeded.");
  }
}
