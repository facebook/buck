/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.kotlin;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class KotlinLibraryIntegrationTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public Timeout timeout = Timeout.seconds(240);

  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "kotlin_library_description", tmp);
    workspace.setUp();
    workspace.addTemplateToWorkspace(Paths.get("test/com/facebook/buck/toolchains/kotlin"));
    KotlinTestAssumptions.assumeCompilerAvailable(workspace.asCell().getBuckConfig());
  }

  @Test
  public void shouldCompileKotlinClass() {
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
  public void shouldCompileKotlinClassWithOverriddenCompiler() throws Exception {
    workspace.copyFile(
        "kotlinc/libexec/lib/kotlin-compiler.jar", "toolchain_override/kotlin-compiler.jar");
    workspace.addBuckConfigLocalOption(
        "kotlin", "toolchain_compiler_jar", "//toolchain_override:compiler");

    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/good:example");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldFailToCompileGivenABadCompilerOverride() throws Exception {
    workspace.addBuckConfigLocalOption(
        "kotlin", "toolchain_compiler_jar", "//toolchain_override:nonexistent");

    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/good:example");
    buildResult.assertFailure("Should fail to find compiler JAR");
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
              "META-INF/com.example.ap.kotlinapgenjava.example.kotlin_module",
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
        new JarFile(jarFile.toString())
            .getEntry("com/example/ap/purekotlinap/Example_kaptgen.class"));
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

  @Test
  public void shouldAnnotationProcessClassesUsingKapt() {
    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//com/example/ap/annotation-processing-tool-kapt:kotlin");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldAnnotationProcessClassesUsingKsp() {
    ProcessResult buildResult =
        workspace.runBuckCommand("build", "//com/example/ap/annotation-processing-tool-ksp:kotlin");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldAnnotationProcessClassesUsingKspAndKapt() {
    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//com/example/ap/annotation-processing-tool-ksp-and-kapt:kotlin");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  // TODO (T107033703): Re-enable this after checking why this fails only on windows machines
  // @Test
  public void shouldAnnotationProcessClassesUsingKspMultiRound() {
    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//com/example/ap/annotation-processing-tool-ksp-multiround:kotlin");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldAnnotationProcessClassesUsingKspMultiRoundToKapt() {
    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//com/example/ap/annotation-processing-tool-ksp-multiround-to-kapt:kotlin");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldAnnotationProcessClassesMultiRoundFromKaptToKsp() throws IOException {
    Path jarFile =
        workspace.buildAndReturnOutput(
            "//com/example/ap/annotation-processing-tool-kapt-multiround-to-ksp:kotlin");
    JarFile outputJar = new JarFile(jarFile.toString());

    // We're getting only the KAPT generated class, since our current KSP integration doesn't
    // support processing code generated by KAPT for the same module.
    // TODO: Update this test after completing T106108430
    assertNotNull(
        outputJar.getEntry("com/example/ap/KotlinClassForMultiroundFromKaptToKsp_kaptgen.class"));
    assertNull(
        outputJar.getEntry(
            "com/example/ap/KotlinClassForMultiroundFromKaptToKsp_kaptgen_kspgen.class"));
  }

  @Test
  public void shouldAnnotationProcessClassesUsingJavac() {
    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//com/example/ap/annotation-processing-tool-javac:kotlin");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldAnnotationProcessClassesUsingJavacWhenNoKotlinSources() {
    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//com/example/ap/annotation-processing-tool-javac:java_sources_only");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldCompileLibraryWithDependencyOnAnother() {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/child:child");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldFailToCompileInvalidKotlinCode() {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/bad:fail");
    buildResult.assertFailure();
  }

  @Test
  public void shouldCompileMixedJavaAndKotlinSources() {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/mixed:example");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldCompileKotlinSrcZip() {
    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/zip:zip");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldPassApoptionsToKaptViaAnnotationProcessorParams() {
    ProcessResult buildResult =
        workspace.runBuckCommand("build", "//com/example/ap/kapt-apoptions:kotlin-ap-params");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldCompileKotlinClassWithTarget8() {
    ProcessResult buildResult =
        workspace.runBuckCommand("build", "//com/example/good:example_with_target");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldCompileKotlinClassWithPlugins() {
    ProcessResult buildResult =
        workspace.runBuckCommand("build", "//com/example/compilerplugin:example");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldFailToCompileKotlinClassWithoutNecessaryPlugin() {
    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//com/example/compilerplugin:example_without_necessary_plugin");
    buildResult.assertFailure();
  }

  @Test
  public void shouldFailToCompileKotlinClassWithPluginsAndMissingOptions() {
    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//com/example/compilerplugin:example_with_missing_options");
    buildResult.assertFailure();
  }

  @Test
  public void shouldFailToCompileKotlinClassWithPluginsAndWrongOptions() {
    ProcessResult buildResult =
        workspace.runBuckCommand(
            "build", "//com/example/compilerplugin:example_with_wrong_options");
    buildResult.assertFailure();
  }

  @Test
  public void shouldDetectUnusedDependencies() {
    ProcessResult buildResult =
        workspace.runBuckCommand("build", "//com/example/deps/binary:binary");
    buildResult.assertSuccess("Build should have succeeded.");
    assertTrue(
        buildResult
            .getStderr()
            .contains(
                String.format(
                    "Target //com/example/deps/binary:binary_lib is declared with unused targets in deps: %n"
                        + "//com/example/deps/a:a%n%n")));
    assertTrue(
        buildResult
            .getStderr()
            .contains(
                String.format(
                    "Target //com/example/deps/a:a is declared with unused targets in deps: %n"
                        + "//com/example/deps/c:c%n%n")));
  }

  @Test
  public void shouldGenerateClassUsageFile() throws IOException {
    BuildTarget bizTarget = BuildTargetFactory.newInstance("//com/example/classusage:biz");
    ProcessResult buildResult =
        workspace.runBuckCommand("build", bizTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Build should have succeeded.");

    RelPath bizGenDir =
        BuildPaths.getGenDir(workspace.getProjectFileSystem().getBuckPaths(), bizTarget)
            .getParent();
    Path kotlinClassUsageFilePath =
        bizGenDir.resolve(
            String.format("lib__%s__output/kotlin-used-classes.json", bizTarget.getShortName()));
    Path javaClassUsageFilePath =
        bizGenDir.resolve(
            String.format("lib__%s__output/used-classes.json", bizTarget.getShortName()));

    List<String> kotlinClassUsageLines =
        Files.readAllLines(workspace.getPath(kotlinClassUsageFilePath), UTF_8);
    List<String> javaClassUsageLines =
        Files.readAllLines(workspace.getPath(javaClassUsageFilePath), UTF_8);
    assertEquals("Expected just one line of JSON", 1, kotlinClassUsageLines.size());
    assertEquals("Expected just one line of JSON", 1, javaClassUsageLines.size());
    JsonNode kotlinClassUsage = ObjectMappers.READER.readTree(kotlinClassUsageLines.get(0));
    JsonNode javaClassUsage = ObjectMappers.READER.readTree(javaClassUsageLines.get(0));

    BuildTarget utilTarget = BuildTargetFactory.newInstance("//com/example/classusage:util");
    String utilJarPath =
        MorePaths.pathWithPlatformSeparators(
            BuildPaths.getGenDir(workspace.getProjectFileSystem().getBuckPaths(), utilTarget)
                .getParent()
                .resolve(String.format("lib__%1$s__output/%1$s.jar", utilTarget.getShortName())));
    String utilClassPath =
        MorePaths.pathWithPlatformSeparators("com/example/classusage/Util.class");

    assertEquals(
        javaClassUsage.toString(),
        String.format("{\"%s\":{\"%s\":1}}", utilJarPath, utilClassPath));

    String kotlinJarPath =
        MorePaths.pathWithPlatformSeparators("kotlinc/libexec/lib/kotlin-stdlib.jar");
    String kotlinClassPath = MorePaths.pathWithPlatformSeparators("kotlin/Unit.class");

    assertEquals(
        kotlinClassUsage.toString(),
        String.format(
            "{\"%s\":{\"%s\":1},\"%s\":{\"%s\":1}}",
            utilJarPath, utilClassPath, kotlinJarPath, kotlinClassPath));
  }

  @Test
  public void shouldNotGenerateClassUsageFileForKAPTTarget() throws IOException {
    BuildTarget bizTarget =
        BuildTargetFactory.newInstance("//com/example/classusage:biz_with_kapt");
    ProcessResult buildResult =
        workspace.runBuckCommand("build", bizTarget.getFullyQualifiedName());
    buildResult.assertSuccess("Build should have succeeded.");

    RelPath bizGenDir =
        BuildPaths.getGenDir(workspace.getProjectFileSystem().getBuckPaths(), bizTarget)
            .getParent();
    Path kotlinClassUsageFilePath =
        bizGenDir.resolve(
            String.format("lib__%s__output/kotlin-used-classes.json", bizTarget.getShortName()));
    Path javaClassUsageFilePath =
        bizGenDir.resolve(
            String.format("lib__%s__output/used-classes.json", bizTarget.getShortName()));

    assertTrue(Files.notExists(workspace.getPath(kotlinClassUsageFilePath)));
    assertTrue(Files.notExists(workspace.getPath(javaClassUsageFilePath)));
  }
}
