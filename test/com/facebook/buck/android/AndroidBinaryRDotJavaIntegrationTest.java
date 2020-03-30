/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.android;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hamcrest.collection.IsIn;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidBinaryRDotJavaIntegrationTest {

  private static final String SIMPLE_TARGET = "//apps/multidex:app";

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  @Before
  public void setUp() throws IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidBinaryRDotJavaIntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    AssumeAndroidPlatform.get(workspace).assumeNdkIsAvailable();
    filesystem = workspace.getProjectFileSystem();
  }

  @Test
  public void testApkWithNoResourcesBuildsCorrectly() {
    workspace.runBuckBuild("//apps/sample:app_with_no_res").assertSuccess();
    workspace.runBuckBuild("//apps/sample:app_with_no_res_or_predex").assertSuccess();
  }

  @Test
  public void testApkWithNoResourcesBuildsCorrectlyWithAapt2() throws Exception {
    AssumeAndroidPlatform.get(workspace).assumeAapt2WithOutputTextSymbolsIsAvailable();
    workspace.runBuckBuild("//apps/sample:app_aapt2_with_no_res").assertSuccess();
  }

  @Test
  public void testSimpleAapt2App() throws Exception {
    AssumeAndroidPlatform.get(workspace).assumeAapt2WithOutputTextSymbolsIsAvailable();

    ImmutableMap<String, Path> outputs =
        workspace.buildMultipleAndReturnOutputs(
            "//apps/sample:app_with_aapt2",
            "//apps/sample:disassemble_app_with_aapt2",
            "//apps/sample:resource_dump_app_with_aapt2");

    ZipInspector zipInspector = new ZipInspector(outputs.get("//apps/sample:app_with_aapt2"));
    zipInspector.assertFileExists("res/drawable/tiny_black.png");
    zipInspector.assertFileExists("res/layout/top_layout.xml");
    zipInspector.assertFileExists("assets/asset_file.txt");

    zipInspector.assertFileIsNotCompressed("res/drawable/tiny_black.png");

    Map<String, String> rDotJavaContents =
        parseRDotJavaSmali(outputs.get("//apps/sample:disassemble_app_with_aapt2"));
    Map<String, String> resourceBundleContents =
        parseResourceDump(outputs.get("//apps/sample:resource_dump_app_with_aapt2"));
    assertEquals(
        resourceBundleContents.get("string/title"),
        rDotJavaContents.get("com/sample2/R$string:title"));
    assertEquals(
        resourceBundleContents.get("layout/top_layout"),
        rDotJavaContents.get("com/sample/R$layout:top_layout"));
    assertEquals(
        resourceBundleContents.get("drawable/app_icon"),
        rDotJavaContents.get("com/sample/R$drawable:app_icon"));
  }

  @Test
  public void testResourcesTrimming() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    // Enable trimming.
    workspace.replaceFileContents(
        "apps/multidex/BUCK", "# ARGS_FOR_APP", "trim_resource_ids = True,  # ARGS_FOR_APP");
    workspace.runBuckCommand("build", "//apps/multidex:disassemble_app_r_dot_java").assertSuccess();
    // Make sure we only see what we expect.
    verifyTrimmedRDotJava(ImmutableSet.of("top_layout", "title"));

    // Make a change.
    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java", "R.layout.top_layout", "0 /* NO RESOURCE HERE */");

    // Make sure everything gets rebuilt, and we only see what we expect.
    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", "//apps/multidex:disassemble_app_r_dot_java").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//apps/multidex:app#compile_uber_r_dot_java");
    buildLog.assertTargetBuiltLocally(
        "//apps/multidex:app#d8,dexing,rtype__primarydex,split_uber_r_dot_java_jar");
    verifyTrimmedRDotJava(ImmutableSet.of("title"));

    // Turn off trimming and turn on exopackage, and rebuilt.
    workspace.replaceFileContents(
        "apps/multidex/BUCK",
        "trim_resource_ids = True,  # ARGS_FOR_APP",
        "exopackage_modes = ['secondary_dex'],  # ARGS_FOR_APP");
    workspace.runBuckCommand("build", SIMPLE_TARGET).assertSuccess();

    // Make a change.
    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java", "0 /* NO RESOURCE HERE */", "R.layout.top_layout");

    // rebuilt and verify that we get an ABI hit.
    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", SIMPLE_TARGET).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetHadMatchingInputRuleKey(SIMPLE_TARGET);
  }

  @Test
  public void testResourcesTrimmingWithPattern() throws IOException {
    // Enable trimming.
    workspace.replaceFileContents(
        "apps/multidex/BUCK",
        "# ARGS_FOR_APP",
        "keep_resource_pattern = '^app_.*', trim_resource_ids = True,  # ARGS_FOR_APP");
    workspace.runBuckCommand("build", "//apps/multidex:disassemble_app_r_dot_java").assertSuccess();
    // Make sure we only see what we expect.
    verifyTrimmedRDotJava(ImmutableSet.of("app_icon", "app_name", "top_layout", "title"));

    // Make a change.
    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java", "R.layout.top_layout", "0 /* NO RESOURCE HERE */");

    // Make sure everything gets rebuilt, and we only see what we expect.
    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", "//apps/multidex:disassemble_app_r_dot_java").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//apps/multidex:app#compile_uber_r_dot_java");
    buildLog.assertTargetBuiltLocally(
        "//apps/multidex:app#d8,dexing,rtype__primarydex,split_uber_r_dot_java_jar");
    verifyTrimmedRDotJava(ImmutableSet.of("app_icon", "app_name", "title"));
  }

  private static final Pattern SMALI_PUBLIC_CLASS_PATTERN =
      Pattern.compile("\\.class public L([\\w/$]+);");
  private static final Pattern SMALI_STATIC_FINAL_INT_PATTERN =
      Pattern.compile("\\.field public static final (\\w+):I = (0x[0-9A-fa-f]+)");

  private void verifyTrimmedRDotJava(ImmutableSet<String> expected) throws IOException {
    List<String> lines =
        filesystem.readLines(
            BuildTargetPaths.getGenPath(
                    filesystem,
                    BuildTargetFactory.newInstance("//apps/multidex:disassemble_app_r_dot_java"),
                    "%s")
                .resolve("all_r_fields.smali"));

    ImmutableSet.Builder<String> found = ImmutableSet.builder();
    for (String line : lines) {
      Matcher m = SMALI_STATIC_FINAL_INT_PATTERN.matcher(line);
      assertTrue("Could not match line: " + line, m.matches());
      assertThat(m.group(1), IsIn.in(expected));
      found.add(m.group(1));
    }
    assertEquals(expected, found.build());
  }

  private Map<String, String> parseRDotJavaSmali(Path smaliPath) throws IOException {
    List<String> lines = filesystem.readLines(smaliPath);
    ImmutableMap.Builder<String, String> output = ImmutableMap.builder();
    String currentClass = null;
    for (String line : lines) {
      Matcher m;

      m = SMALI_PUBLIC_CLASS_PATTERN.matcher(line);
      if (m.matches()) {
        currentClass = m.group(1);
        continue;
      }

      m = SMALI_STATIC_FINAL_INT_PATTERN.matcher(line);
      if (m.matches()) {
        output.put(currentClass + ":" + m.group(1), m.group(2));
        continue;
      }
    }
    return output.build();
  }

  private static final Pattern RESOURCE_DUMP_SPEC_PATTERN =
      Pattern.compile(" *spec resource (0x[0-9A-fa-f]+) [\\w.]+:(\\w+/\\w+):.*");

  private Map<String, String> parseResourceDump(Path dumpPath) throws IOException {
    List<String> lines = filesystem.readLines(dumpPath);
    ImmutableMap.Builder<String, String> output = ImmutableMap.builder();
    for (String line : lines) {
      Matcher m = RESOURCE_DUMP_SPEC_PATTERN.matcher(line);
      if (m.matches()) {
        output.put(m.group(2), m.group(1));
      }
    }
    return output.build();
  }

  @Test
  public void testResourceSplitting() throws IOException {
    ImmutableMap<String, Path> outputs =
        workspace.buildMultipleAndReturnOutputs(
            "//apps/multidex:disassemble_big_r_dot_java_primary",
            "//apps/multidex:disassemble_big_r_dot_java_classes2",
            "//apps/multidex:disassemble_big_r_dot_java_classes3");

    Set<String> primaryClasses =
        ImmutableSet.copyOf(
            filesystem.readLines(
                outputs.get("//apps/multidex:disassemble_big_r_dot_java_primary")));
    assertThat(primaryClasses, hasItem("Lcom/primary/R$id;"));
    assertThat(primaryClasses, hasItem("Lcom/primary/R$string;"));
    assertThat(primaryClasses, hasItem("Lcom/primary/R$color;"));

    // This is kind of brittle.  We assume that there are exactly 2 secondary dexes.
    // Better would be to use ZipInspector to count the dexes, verify there are at least 2,
    // and use baksmali directly to diassemble them.  The last part turns out to be the trickiest
    // because baksmali is shipped as an uber-jar.
    Set<String> secondary2Classes =
        ImmutableSet.copyOf(
            filesystem.readLines(
                outputs.get("//apps/multidex:disassemble_big_r_dot_java_classes2")));
    Set<String> secondary3Classes =
        ImmutableSet.copyOf(
            filesystem.readLines(
                outputs.get("//apps/multidex:disassemble_big_r_dot_java_classes3")));
    Set<String> secondaryClasses = Sets.union(secondary2Classes, secondary3Classes);
    assertThat(secondaryClasses, hasItem("Lcom/secondary1/R$id;"));
    assertThat(secondaryClasses, hasItem("Lcom/secondary1/R$string;"));
    assertThat(secondaryClasses, hasItem("Lcom/secondary1/R$color;"));
    assertThat(secondaryClasses, hasItem("Lcom/secondary2/R$id;"));
    assertThat(secondaryClasses, hasItem("Lcom/secondary2/R$string;"));
    assertThat(secondaryClasses, hasItem("Lcom/secondary2/R$color;"));
  }

  @Test
  public void testSkipCrunchPngs() throws IOException {
    // By default, images are crunched. This test also makes sure that the image we're using is
    // genuinely an unoptimized image so that the next test is actually testing something real.
    long originalSize =
        Files.size(workspace.getPath("res/com/sample/top/res/drawable/uncrunched.png"));
    long crunchedSize =
        buildAndGetOutputEntryLength("//apps/sample:app_with_aapt2", "res/drawable/uncrunched.png");
    assertNotEquals(originalSize, crunchedSize);

    // If we pass in the option to skip crunching, the image size should stay the same.
    workspace.addBuckConfigLocalOption("android", "aapt_compile_skip_crunch_pngs_default", "true");
    long uncrunchedSize =
        buildAndGetOutputEntryLength("//apps/sample:app_with_aapt2", "res/drawable/uncrunched.png");
    assertEquals(originalSize, uncrunchedSize);
  }

  private long buildAndGetOutputEntryLength(String target, String zipEntry) throws IOException {
    return new ZipInspector(buildApk(target)).getSize(zipEntry);
  }

  @Test
  public void testFailOnLegacyErrors() throws IOException {
    AssumeAndroidPlatform.get(workspace).assumeBuildToolsVersionIsAtLeast("26");
    // As of 2019-11-14, aapt2 has only two error cases that --legacy suppresses:
    //    1) Multiple positional arguments in a resource string are not allowed.
    //    2) Multiple periods are not allowed in resource filenames.
    // The latter check was added in aapt2 in 28.0.2, the former was in at least 26.0.0
    // (possibly earlier).

    // Since we can't guarantee which version of aapt2 buck runs tests with, we'll test for
    // catching the first case, since it's been there for longer.

    // With --legacy (the default), the string resource 'positional_args' produces a warning
    workspace.runBuckBuild("//apps/sample:app_with_aapt2").assertSuccess();

    // Without --legacy, 'positional_args' causes a aapt2 compile to fail.
    workspace.addBuckConfigLocalOption("android", "aapt_fail_on_legacy_errors", "true");
    workspace.runBuckBuild("//apps/sample:app_with_aapt2").assertFailure();
  }

  private Path buildApk(String target) throws IOException {
    ImmutableMap<String, Path> outputs = workspace.buildMultipleAndReturnOutputs(target);
    return outputs.get(target);
  }

  @Test
  public void testNoResourceRemoval() throws Exception {
    // Resource removal was added in 28.0.2, and the --no-resource-removal flag was
    // added in 29.0.0.
    AssumeAndroidPlatform.get(workspace).assumeBuildToolsVersionIsAtLeast("29");

    // Without the flag (default), the missing_in_default resource is removed from the apk
    Path apk = buildApk("//apps/sample:app_with_aapt2");
    assertDoesNotHaveResource(apk, "string/missing_in_default");

    // With the flag, the missing_in_default resource is present
    workspace.addBuckConfigLocalOption("android", "aapt_no_resource_removal", "true");
    apk = buildApk("//apps/sample:app_with_aapt2");
    assertHasResource(apk, "string/missing_in_default");
  }

  private String dumpResources(Path apk) throws Exception {
    AndroidSdkResolver sdkResolver = AndroidSdkResolver.get(workspace).get();
    ProcessExecutor.Result result =
        workspace.runCommand(
            ImmutableList.of(
                sdkResolver.getAapt2().getPath(), "dump", "resources", apk.toString()));

    return result.getStdout().get();
  }

  private void assertHasResource(Path apk, String resourceName) throws Exception {
    String output = dumpResources(apk);
    assertTrue(
        "Apk "
            + apk
            + " expected to have resource `"
            + resourceName
            + "`. But not found. Output: "
            + output,
        output.contains(resourceName));
  }

  private void assertDoesNotHaveResource(Path apk, String resourceName) throws Exception {
    String output = dumpResources(apk);
    assertFalse(
        "Apk "
            + apk
            + " expected not to have resource `"
            + resourceName
            + "`. But found. Output: "
            + output,
        output.contains(resourceName));
  }

  /** Verifies that we don't stamp workspace specific absolute paths into .flata files. */
  @Test
  public void testFlataContainsRelativePaths() throws IOException {
    // The path to the .flata file generated by aapt2 compile
    String flataPath =
        BuildTargetPaths.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance("//res/com/sample/aapt2:aapt2#aapt2_compile"),
                "%s")
            .resolve("resources.flata")
            .toString();

    workspace.runBuckBuild("//apps/sample:app_with_aapt2").assertSuccess();
    Path firstFlataFile = workspace.getPath(flataPath);
    byte[] firstFlataBytes = Files.readAllBytes(firstFlataFile);

    // Create a second workspace in a different root directory
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "android_project", workspace.getPath("xx_altwork"));
    workspace.setUp();
    workspace.runBuckBuild("//apps/sample:app_with_aapt2").assertSuccess();
    Path secondFlataFile = workspace.getPath(flataPath);
    byte[] secondFlataBytes = Files.readAllBytes(secondFlataFile);

    assertArrayEquals(
        "Expected binary files " + firstFlataFile + " and " + secondFlataFile + " to be identical",
        firstFlataBytes,
        secondFlataBytes);
  }

  // TODO(bduff): find a better place for this and other aapt2 tests
  @Test
  public void aapt2FilteringWithAapt1Fails() throws IOException {
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();

    workspace.runBuckBuild("//apps/sample_wrong_aapt:sample_wrong_aapt").assertFailure();
  }

  @Test
  public void aapt2FilteringStripsLocales() throws Exception {
    AssumeAndroidPlatform.get(workspace).assumeBuildToolsVersionIsAtLeast("29");

    // Disable resource removal.
    workspace.addBuckConfigLocalOption("android", "aapt_no_resource_removal", "true");

    // First check that we include the pt locale in non-filtered builds.
    Path apk = buildApk("//apps/sample:app_with_aapt2");
    assertHasResource(apk, "(pt)");

    // Now build the filtered version, and check that it doesn't contain the pt locale
    apk = buildApk("//apps/sample:app_with_aapt2_locale_filtering");

    assertDoesNotHaveResource(apk, "(pt)");
  }
}
