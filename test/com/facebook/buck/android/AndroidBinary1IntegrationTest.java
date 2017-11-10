/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android;

import static com.facebook.buck.testutil.RegexMatcher.containsPattern;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.zip.ZipConstants;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.Matchers;
import org.hamcrest.collection.IsIn;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidBinary1IntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths(true);

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  private static final String SIMPLE_TARGET = "//apps/multidex:app";
  private static final String RAW_DEX_TARGET = "//apps/multidex:app-art";

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidBinary1IntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testRawSplitDexHasSecondary() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", RAW_DEX_TARGET);
    result.assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(RAW_DEX_TARGET), "%s.apk")));
    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/metadata.txt");

    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileExists("classes2.dex");

    zipInspector.assertFileExists("classes.dex");
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
  }

  @Test
  public void testProguardDontObfuscateGeneratesMappingFile() throws IOException {
    String target = "//apps/sample:app_proguard_dontobfuscate";
    workspace.runBuckCommand("build", target).assertSuccess();

    Path mapping =
        workspace.getPath(
            BuildTargets.getGenPath(
                filesystem, BuildTargetFactory.newInstance(target), "%s/proguard/mapping.txt"));
    assertTrue(Files.exists(mapping));
  }

  @Test
  public void testApksHaveDeterministicTimestamps() throws IOException {
    String target = "//apps/sample:app";
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Path apk =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
    Date dosEpoch = new Date(ZipUtil.dosToJavaTime(ZipConstants.DOS_FAKE_TIME));
    try (ZipInputStream is = new ZipInputStream(Files.newInputStream(apk))) {
      for (ZipEntry entry = is.getNextEntry(); entry != null; entry = is.getNextEntry()) {
        assertThat(entry.getName(), new Date(entry.getTime()), Matchers.equalTo(dosEpoch));
      }
    }
  }

  @Test
  public void testLibraryMetadataChecksum() throws IOException {
    String target = "//apps/sample:app_cxx_lib_asset";
    workspace.runBuckCommand("build", target).assertSuccess();
    Path pathToZip =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
    ZipFile file = new ZipFile(pathToZip.toFile());
    ZipEntry metadata = file.getEntry("assets/lib/metadata.txt");
    assertNotNull(metadata);

    BufferedReader contents =
        new BufferedReader(new InputStreamReader(file.getInputStream(metadata)));
    String line = contents.readLine();
    byte[] buffer = new byte[512];
    while (line != null) {
      // Each line is of the form <filename> <filesize> <SHA256 checksum>
      String[] tokens = line.split(" ");
      assertSame(tokens.length, 3);
      String filename = tokens[0];
      int filesize = Integer.parseInt(tokens[1]);
      String checksum = tokens[2];

      ZipEntry lib = file.getEntry("assets/lib/" + filename);
      assertNotNull(lib);
      InputStream is = file.getInputStream(lib);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      while (filesize > 0) {
        int read = is.read(buffer, 0, Math.min(buffer.length, filesize));
        assertTrue(read >= 0);
        out.write(buffer, 0, read);
        filesize -= read;
      }
      String actualChecksum = Hashing.sha256().hashBytes(out.toByteArray()).toString();
      assertEquals(checksum, actualChecksum);
      is.close();
      out.close();
      line = contents.readLine();
    }
    file.close();
    contents.close();
  }

  @Test
  public void testStripRulesAreShared() throws IOException {
    workspace.runBuckCommand("build", "//apps/sample:app_cxx_lib_asset").assertSuccess();
    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", "//apps/sample:app_cxx_different_rule_name").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    for (BuildTarget target : buildLog.getAllTargets()) {
      String rawTarget = target.toString();
      if (rawTarget.contains("libgnustl_shared.so")) {
        // Stripping the C++ runtime is currently not shared.
        continue;
      }
      if (rawTarget.contains("strip")) {
        buildLog.assertNotTargetBuiltLocally(rawTarget);
      }
    }
  }

  @Test
  public void testApkWithNoResourcesBuildsCorrectly() throws IOException {
    workspace.runBuckBuild("//apps/sample:app_with_no_res").assertSuccess();
    workspace.runBuckBuild("//apps/sample:app_with_no_res_or_predex").assertSuccess();
  }

  @Test
  public void testSimpleAapt2App() throws Exception {
    AssumeAndroidPlatform.assumeAapt2WithOutputTextSymbolsIsAvailable();

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

  private static final Pattern SMALI_PUBLIC_CLASS_PATTERN =
      Pattern.compile("\\.class public L([\\w/$]+);");

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
  public void testSimpleD8App() throws IOException {
    workspace.runBuckBuild("//apps/sample:app_with_d8").assertSuccess();
  }

  @Test
  public void testResourceOverrides() throws IOException {
    Path path = workspace.buildAndReturnOutput("//apps/sample:strings_dump_overrides");
    assertThat(
        workspace.getFileContents(path),
        containsPattern(Pattern.compile("^String #[0-9]*: Real App Name$", Pattern.MULTILINE)));
  }

  @Test
  public void testResourceOverridesAapt2() throws Exception {
    AssumeAndroidPlatform.assumeAapt2WithOutputTextSymbolsIsAvailable();
    workspace.replaceFileContents(
        "apps/sample/BUCK", "'aapt1',  # app_with_res_overrides", "'aapt2',");

    testResourceOverrides();
  }

  @Test
  public void testApkEmptyResDirectoriesBuildsCorrectly() throws IOException {
    workspace.runBuckBuild("//apps/sample:app_with_aar_and_no_res").assertSuccess();
  }

  @Test
  public void testNativeLibGeneratedProguardConfigIsUsedByProguard() throws IOException {
    String target = "//apps/sample:app_with_native_lib_proguard";
    workspace.runBuckBuild(target).assertSuccess();

    Path generatedConfig =
        workspace.getPath(
            BuildTargets.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance(target)
                    .withFlavors(AndroidBinaryGraphEnhancer.NATIVE_LIBRARY_PROGUARD_FLAVOR),
                NativeLibraryProguardGenerator.OUTPUT_FORMAT));

    Path proguardDir =
        workspace.getPath(
            BuildTargets.getGenPath(
                filesystem, BuildTargetFactory.newInstance(target), "%s/proguard"));

    Path proguardCommandLine = proguardDir.resolve("command-line.txt");
    // Check that the proguard command line references the native lib proguard config.
    assertTrue(workspace.getFileContents(proguardCommandLine).contains(generatedConfig.toString()));
    assertEquals(
        workspace.getFileContents("native/proguard_gen/expected.pro"),
        workspace.getFileContents(generatedConfig));
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
    buildLog.assertTargetBuiltLocally("//apps/multidex:app#dex,dex_uber_r_dot_java");
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
    buildLog.assertTargetBuiltLocally("//apps/multidex:app#dex,dex_uber_r_dot_java");
    verifyTrimmedRDotJava(ImmutableSet.of("app_icon", "app_name", "title"));
  }

  private static final Pattern SMALI_STATIC_FINAL_INT_PATTERN =
      Pattern.compile("\\.field public static final (\\w+):I = (0x[0-9A-fa-f]+)");

  private void verifyTrimmedRDotJava(ImmutableSet<String> expected) throws IOException {
    List<String> lines =
        filesystem.readLines(
            Paths.get("buck-out/gen/apps/multidex/disassemble_app_r_dot_java/all_r_fields.smali"));

    ImmutableSet.Builder<String> found = ImmutableSet.builder();
    for (String line : lines) {
      Matcher m = SMALI_STATIC_FINAL_INT_PATTERN.matcher(line);
      assertTrue("Could not match line: " + line, m.matches());
      assertThat(m.group(1), IsIn.in(expected));
      found.add(m.group(1));
    }
    assertEquals(expected, found.build());
  }
}
