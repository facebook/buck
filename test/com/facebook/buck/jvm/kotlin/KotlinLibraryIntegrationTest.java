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

import static com.facebook.buck.io.file.MorePaths.pathWithPlatformSeparators;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildPaths;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
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
  public void shouldCompileKotlinClassWithExternalCompiler() throws Exception {
    assumeThat(
        "TODO T128936075 Windows External Kotlinc", Platform.detect(), not(Platform.WINDOWS));

    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "kotlin",
            ImmutableMap.of(
                "external", "true",
                "kotlin_home", "kotlinc/libexec/")));

    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/good:example");
    buildResult.assertSuccess("Build should have succeeded.");
  }

  @Test
  public void shouldFailToCompileKotlinClassWithBadExternalCompiler() throws Exception {
    assumeThat(
        "TODO T128936075 Windows External Kotlinc", Platform.detect(), not(Platform.WINDOWS));

    workspace.addBuckConfigLocalOptions(
        ImmutableMap.of(
            "kotlin",
            ImmutableMap.of(
                "external", "true",
                "kotlin_home", "i_dont_exist")));

    ProcessResult buildResult = workspace.runBuckCommand("build", "//com/example/good:example");
    buildResult.assertFailure("Build should fail to resolve compiler.");
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
  @Ignore("T130370064")
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
  @Ignore("T130370064")
  public void shouldGenerateClassUsageFile() throws IOException {
    String bizTargetFqn = "//com/example/classusage:biz";
    ProcessResult buildResult = workspace.runBuckCommand("build", bizTargetFqn);
    buildResult.assertSuccess("Build should have succeeded.");

    assertTrue(
        "Should generate kotlin-used-classes-tmp.json",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "kotlin-used-classes-tmp.json"))));
    assertFalse(
        "Should not generate kapt-used-classes-tmp.txt for no-kapt target",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "kapt-used-classes-tmp.txt"))));

    List<String> kotlinClassUsageLines =
        Files.readAllLines(
            workspace.getPath(getOutputFilePath(bizTargetFqn, "kotlin-used-classes.json")), UTF_8);
    List<String> javaClassUsageLines =
        Files.readAllLines(
            workspace.getPath(getOutputFilePath(bizTargetFqn, "used-classes.json")), UTF_8);
    assertEquals("Expected just one line of JSON", 1, kotlinClassUsageLines.size());
    assertEquals("Expected just one line of JSON", 1, javaClassUsageLines.size());

    Path utilJarPath = getOutputJarPath("//com/example/classusage:util");
    assertEquals(
        Map.of(
            pathWithPlatformSeparators(utilJarPath),
            Map.of(pathWithPlatformSeparators("com/example/classusage/Util.class"), 1)),
        ObjectMappers.READER.readValue(
            ObjectMappers.createParser(javaClassUsageLines.get(0)),
            new TypeReference<Map<String, Map<String, Integer>>>() {}));

    assertEquals(
        Map.of(
            pathWithPlatformSeparators(utilJarPath),
                ImmutableMap.of(pathWithPlatformSeparators("com/example/classusage/Util.class"), 5),
            pathWithPlatformSeparators("kotlinc/libexec/lib/kotlin-stdlib.jar"),
                ImmutableMap.of(pathWithPlatformSeparators("kotlin/Unit.class"), 2)),
        ObjectMappers.READER.readValue(
            ObjectMappers.createParser(kotlinClassUsageLines.get(0)),
            new TypeReference<Map<String, Map<String, Integer>>>() {}));
  }

  @Test
  @Ignore("T130370064")
  public void shouldNotGenerateClassUsageFileForKaptTarget() throws IOException {
    String bizTargetFqn = "//com/example/classusage:biz_with_kapt";
    ProcessResult buildResult = workspace.runBuckCommand("build", bizTargetFqn);
    buildResult.assertSuccess("Build should have succeeded.");

    assertFalse(
        "Should not generate kotlin-used-classes-tmp.json",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "kotlin-used-classes-tmp.json"))));
    assertFalse(
        "Should not generate kapt-used-classes-tmp.txt",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "kapt-used-classes-tmp.txt"))));
    assertFalse(
        "Should not generate used-classes.json",
        Files.exists(workspace.getPath(getOutputFilePath(bizTargetFqn, "used-classes.json"))));
    assertFalse(
        "Should not generate kotlin-used-classes.json",
        Files.exists(
            workspace.getPath(getOutputFilePath(bizTargetFqn, "kotlin-used-classes.json"))));
  }

  @Test
  @Ignore("T130370064")
  public void shouldGenerateClassUsageFileForKaptTargetIfEnabled() throws IOException {
    String bizTargetFqn = "//com/example/classusage:biz_with_kapt";
    workspace.addBuckConfigLocalOption("kotlin", "track_class_usage_for_kapt_targets", true);
    ProcessResult buildResult = workspace.runBuckCommand("build", bizTargetFqn);
    buildResult.assertSuccess("Build should have succeeded.");

    assertTrue(
        "Should generate kotlin-used-classes-tmp.json",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "kotlin-used-classes-tmp.json"))));
    assertTrue(
        "Should generate kapt-used-classes-tmp.txt for kapt target",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "kapt-used-classes-tmp.txt"))));

    List<String> kotlinClassUsageLines =
        Files.readAllLines(
            workspace.getPath(getOutputFilePath(bizTargetFqn, "kotlin-used-classes.json")), UTF_8);
    List<String> javaClassUsageLines =
        Files.readAllLines(
            workspace.getPath(getOutputFilePath(bizTargetFqn, "used-classes.json")), UTF_8);
    assertEquals("Expected just one line of JSON", 1, kotlinClassUsageLines.size());
    assertEquals("Expected just one line of JSON", 1, javaClassUsageLines.size());

    Path utilJarPath = getOutputJarPath("//com/example/classusage:util");
    assertEquals(
        Map.of(
            pathWithPlatformSeparators(utilJarPath),
            Map.of(pathWithPlatformSeparators("com/example/classusage/Util.class"), 1)),
        ObjectMappers.READER.readValue(
            ObjectMappers.createParser(javaClassUsageLines.get(0)),
            new TypeReference<Map<String, Map<String, Integer>>>() {}));

    assertEquals(
        Map.of(
            pathWithPlatformSeparators(utilJarPath),
            Map.of(pathWithPlatformSeparators("com/example/classusage/Util.class"), 6),
            pathWithPlatformSeparators("kotlinc/libexec/lib/annotations-13.0.jar"),
            Map.of(pathWithPlatformSeparators("org/jetbrains/annotations/NotNull.class"), 1),
            pathWithPlatformSeparators("kotlinc/libexec/lib/kotlin-stdlib.jar"),
            Map.of(
                pathWithPlatformSeparators("kotlin/Metadata.class"), 1,
                pathWithPlatformSeparators("kotlin/SinceKotlin.class"), 1,
                pathWithPlatformSeparators("kotlin/Unit.class"), 2,
                pathWithPlatformSeparators("kotlin/annotation/AnnotationRetention.class"), 1,
                pathWithPlatformSeparators("kotlin/annotation/AnnotationTarget.class"), 1,
                pathWithPlatformSeparators("kotlin/annotation/Retention.class"), 1,
                pathWithPlatformSeparators("kotlin/annotation/Target.class"), 1,
                pathWithPlatformSeparators("kotlin/jvm/JvmName.class"), 1)),
        ObjectMappers.READER.readValue(
            ObjectMappers.createParser(kotlinClassUsageLines.get(0)),
            new TypeReference<Map<String, Map<String, Integer>>>() {}));
  }

  @Test
  @Ignore("T130370064")
  public void shouldNotGenerateClassUsageFileForKaptTargetIfBlocklisted() throws IOException {
    String bizTargetFqn = "//com/example/classusage:biz_with_kapt";
    workspace.addBuckConfigLocalOption("kotlin", "track_class_usage_for_kapt_targets", true);
    workspace.addBuckConfigLocalOption(
        "kotlin",
        "track_class_usage_processor_blocklist",
        "com.example.ap.kotlinap_kapt.AnnotationProcessorKotlin,otherProvider");
    ProcessResult buildResult = workspace.runBuckCommand("build", bizTargetFqn);
    buildResult.assertSuccess("Build should have succeeded.");

    assertFalse(
        "Should not generate kotlin-used-classes-tmp.json",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "kotlin-used-classes-tmp.json"))));
    assertFalse(
        "Should not generate kapt-used-classes-tmp.txt",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "kapt-used-classes-tmp.txt"))));
    assertFalse(
        "Should not generate used-classes.json",
        Files.exists(workspace.getPath(getOutputFilePath(bizTargetFqn, "used-classes.json"))));
    assertFalse(
        "Should not generate kotlin-used-classes.json",
        Files.exists(
            workspace.getPath(getOutputFilePath(bizTargetFqn, "kotlin-used-classes.json"))));
  }

  @Test
  @Ignore("T130370064")
  public void shouldNotGenerateClassUsageFileForKspTarget() throws IOException {
    String bizTargetFqn = "//com/example/classusage:biz_with_ksp";
    ProcessResult buildResult = workspace.runBuckCommand("build", bizTargetFqn);
    buildResult.assertSuccess("Build should have succeeded.");

    assertFalse(
        "Should not generate kotlin-used-classes-tmp.json",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "kotlin-used-classes-tmp.json"))));
    assertFalse(
        "Should not generate ksp-used-classes-tmp.txt",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "ksp-used-classes-tmp.txt"))));
    assertFalse(
        "Should not generate used-classes.json",
        Files.exists(workspace.getPath(getOutputFilePath(bizTargetFqn, "used-classes.json"))));
    assertFalse(
        "Should not generate kotlin-used-classes.json",
        Files.exists(
            workspace.getPath(getOutputFilePath(bizTargetFqn, "kotlin-used-classes.json"))));
  }

  @Test
  @Ignore("T130370064")
  public void shouldGenerateClassUsageFileForKspTargetIfEnabled() throws IOException {
    String bizTargetFqn = "//com/example/classusage:biz_with_ksp";
    workspace.addBuckConfigLocalOption("kotlin", "track_class_usage_for_ksp_targets", true);
    ProcessResult buildResult = workspace.runBuckCommand("build", bizTargetFqn);
    buildResult.assertSuccess("Build should have succeeded.");

    assertTrue(
        "Should generate kotlin-used-classes-tmp.json",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "kotlin-used-classes-tmp.json"))));
    assertTrue(
        "Expected KSP file usage report to contain KspKotlinAnnotation.class",
        Files.readString(
                workspace.getPath(getReportFilePath(bizTargetFqn, "ksp-used-classes-tmp.txt")))
            .contains("KspKotlinAnnotation.class"));

    List<String> kotlinClassUsageLines =
        Files.readAllLines(
            workspace.getPath(getOutputFilePath(bizTargetFqn, "kotlin-used-classes.json")), UTF_8);
    List<String> javaClassUsageLines =
        Files.readAllLines(
            workspace.getPath(getOutputFilePath(bizTargetFqn, "used-classes.json")), UTF_8);
    assertEquals("Expected just one line of JSON", 1, kotlinClassUsageLines.size());
    assertEquals("Expected just one line of JSON", 1, javaClassUsageLines.size());

    Path utilJarPath = getOutputJarPath("//com/example/classusage:util");
    assertEquals(
        Map.of(
            pathWithPlatformSeparators(utilJarPath),
            Map.of(pathWithPlatformSeparators("com/example/classusage/Util.class"), 1)),
        ObjectMappers.READER.readValue(
            ObjectMappers.createParser(javaClassUsageLines.get(0)),
            new TypeReference<Map<String, Map<String, Integer>>>() {}));

    assertEquals(
        Map.of(
            pathWithPlatformSeparators(utilJarPath),
            Map.of(pathWithPlatformSeparators("com/example/classusage/Util.class"), 5),
            pathWithPlatformSeparators("kotlinc/libexec/lib/kotlin-stdlib.jar"),
            Map.of(pathWithPlatformSeparators("kotlin/Unit.class"), 2),
            pathWithPlatformSeparators(
                getOutputJarPath("//com/example/ap/kotlinannotation:annotation-lib")),
            Map.of(
                pathWithPlatformSeparators(
                    "com/example/ap/kotlinannotation/KspKotlinAnnotation.class"),
                1)),
        ObjectMappers.READER.readValue(
            ObjectMappers.createParser(kotlinClassUsageLines.get(0)),
            new TypeReference<Map<String, Map<String, Integer>>>() {}));
  }

  @Test
  @Ignore("T130370064")
  public void shouldNotGenerateClassUsageFileForKspTargetIfBlocklisted() throws IOException {
    String bizTargetFqn = "//com/example/classusage:biz_with_ksp";
    workspace.addBuckConfigLocalOption("kotlin", "track_class_usage_for_ksp_targets", true);
    workspace.addBuckConfigLocalOption(
        "kotlin",
        "track_class_usage_processor_blocklist",
        "KSP:com.example.ap.kotlinap_ksp_dump_file_usage.AnnotationProcessorKotlinKspProvider,KSP:otherProvider");
    ProcessResult buildResult = workspace.runBuckCommand("build", bizTargetFqn);
    buildResult.assertSuccess("Build should have succeeded.");

    assertFalse(
        "Should not generate kotlin-used-classes-tmp.json",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "kotlin-used-classes-tmp.json"))));
    assertFalse(
        "Should not generate ksp-used-classes-tmp.txt",
        Files.exists(
            workspace.getPath(getReportFilePath(bizTargetFqn, "ksp-used-classes-tmp.txt"))));
    assertFalse(
        "Should not generate used-classes.json",
        Files.exists(workspace.getPath(getOutputFilePath(bizTargetFqn, "used-classes.json"))));
    assertFalse(
        "Should not generate kotlin-used-classes.json",
        Files.exists(
            workspace.getPath(getOutputFilePath(bizTargetFqn, "kotlin-used-classes.json"))));
  }

  @Test
  public void shouldNotGenerateJvmAbi() throws IOException {
    String targetFqn = "//com/example/child:child";
    workspace.addBuckConfigLocalOption("kotlin", "use_jvm_abi_gen", false);
    ProcessResult buildResult = workspace.runBuckCommand("build", targetFqn);
    buildResult.assertSuccess("Build should have succeeded.");

    assertFalse(
        "Should not generate jvm-abi",
        Files.exists(
            workspace.getPath(
                JavaAbis.getGenPathForClassAbiPartFromLibraryTarget(getGenDir(targetFqn)))));
  }

  @Test
  public void shouldGenerateJvmAbiIfEnabled() throws IOException {
    String targetFqn = "//com/example/child:child";
    workspace.addBuckConfigLocalOption("kotlin", "use_jvm_abi_gen", true);
    workspace.addBuckConfigLocalOption(
        "kotlin", "kosabi_jvm_abi_gen_plugin", "//com/example/libs:jvm-abi-gen");
    // Though jvm-abi-gen for class-abi won't use these 2 plugins, Kosabi.getPluginOptionsMappings
    // requires all 3 plugins to be valid to return any.
    workspace.addBuckConfigLocalOption(
        "kotlin", "kosabi_stubs_gen_plugin", "//com/example/libs:jvm-abi-gen");
    workspace.addBuckConfigLocalOption(
        "kotlin", "kosabi_applicability_plugin", "//com/example/libs:jvm-abi-gen");
    ProcessResult buildResult = workspace.runBuckCommand("build", targetFqn);
    buildResult.assertSuccess("Build should have succeeded.");

    assertTrue(
        "Should generate jvm-abi",
        Files.exists(
            workspace.getPath(
                JavaAbis.getGenPathForClassAbiPartFromLibraryTarget(getGenDir(targetFqn)))));
  }

  private Path getOutputJarPath(String targetFqn) throws IOException {
    BuildTarget utilTarget = BuildTargetFactory.newInstance(targetFqn);
    return getOutputFilePath(targetFqn, utilTarget.getShortName() + ".jar");
  }

  private Path getOutputFilePath(String targetFqn, String fileName) throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance(targetFqn);
    return getGenDir(targetFqn)
        .getParent()
        .resolve(String.format("lib__%s__output/" + fileName, target.getShortName()));
  }

  private RelPath getGenDir(String targetFqn) throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance(targetFqn);
    return BuildPaths.getGenDir(workspace.getProjectFileSystem().getBuckPaths(), target);
  }

  private Path getReportFilePath(String targetFqn, String fileName) throws IOException {
    BuildTarget target = BuildTargetFactory.newInstance(targetFqn);
    RelPath annotationDir =
        BuildPaths.getAnnotationDir(workspace.getProjectFileSystem(), target).getParent();
    return annotationDir.resolve(
        String.format("__%s_reports__/" + fileName, target.getShortName()));
  }
}
