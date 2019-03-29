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
import static com.facebook.buck.testutil.RegexMatcher.containsRegex;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.android.apksig.ApkVerifier;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DexInspector;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.zip.ZipConstants;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.hamcrest.CoreMatchers;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidBinaryIntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  private static final String SIMPLE_TARGET = "//apps/multidex:app";
  private static final String RES_D8_TARGET = "//apps/multidex:app_with_resources_and_d8";
  private static final String RAW_DEX_TARGET = "//apps/multidex:app-art";
  private static final String APP_REDEX_TARGET = "//apps/sample:app_redex";

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidBinaryIntegrationTest(), "android_project", tmpFolder);
    workspace.setUp();
    setWorkspaceCompilationMode(workspace);
    filesystem = TestProjectFilesystems.createProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testNonExopackageHasSecondary() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(SIMPLE_TARGET), "%s.apk")));

    zipInspector.assertFileExists("assets/secondary-program-dex-jars/metadata.txt");
    zipInspector.assertFileExists("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileDoesNotExist("classes2.dex");

    zipInspector.assertFileExists("classes.dex");
    if (AssumeAndroidPlatform.isArmAvailable()) {
      zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
    }
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_lib.so");
  }

  @Test
  public void testProguardBuild() throws IOException {
    String target = "//apps/multidex:app_with_proguard";
    workspace.runBuckCommand("build", target).assertSuccess();

    ZipInspector zipInspector = new ZipInspector(workspace.buildAndReturnOutput(target));

    zipInspector.assertFileExists("assets/secondary-program-dex-jars/metadata.txt");
    zipInspector.assertFileExists("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileDoesNotExist("classes2.dex");

    zipInspector.assertFileExists("classes.dex");
    if (AssumeAndroidPlatform.isArmAvailable()) {
      zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
    }
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_lib.so");
  }

  @Test
  public void testRawSplitDexHasSecondary() throws IOException {
    ProcessResult result = workspace.runBuckCommand("build", RAW_DEX_TARGET);
    result.assertSuccess();

    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(RAW_DEX_TARGET), "%s.apk")));
    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/metadata.txt");

    zipInspector.assertFileDoesNotExist("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileExists("classes2.dex");

    zipInspector.assertFileExists("classes.dex");
    if (AssumeAndroidPlatform.isArmAvailable()) {
      zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
    }
    zipInspector.assertFileExists("lib/armeabi-v7a/libnative_cxx_lib.so");
    zipInspector.assertFileExists("lib/x86/libnative_cxx_lib.so");
  }

  @Test
  public void testDisguisedExecutableIsRenamedWithNDKPrior17() throws IOException {
    AssumeAndroidPlatform.assumeArmIsAvailable();
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_disguised_exe-16");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi/libmybinary.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libmybinary.so");
    zipInspector.assertFileExists("lib/x86/libmybinary.so");
  }

  @Test
  public void testDisguisedExecutableIsRenamed() throws IOException {
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_disguised_exe");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi-v7a/libmybinary.so");
    zipInspector.assertFileExists("lib/x86/libmybinary.so");
  }

  @Test
  public void testNdkLibraryIsIncludedWithNdkPrior17() throws IOException {
    AssumeAndroidPlatform.assumeArmIsAvailable();
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_ndk_library-16");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi/libfakenative.so");
    zipInspector.assertFileExists("lib/armeabi-v7a/libfakenative.so");
    zipInspector.assertFileExists("lib/mips/libfakenative.so");
    zipInspector.assertFileExists("lib/x86/libfakenative.so");
  }

  @Test
  public void testNdkLibraryIsIncluded() throws IOException {
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_ndk_library");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi-v7a/libfakenative.so");
    zipInspector.assertFileExists("lib/x86/libfakenative.so");
  }

  @Test
  public void testEditingNdkLibraryForcesRebuild() throws IOException, InterruptedException {
    String apkWithNdkLibrary = "//apps/sample:app_with_ndk_library";
    Path output = workspace.buildAndReturnOutput(apkWithNdkLibrary);
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi-v7a/libfakenative.so");

    // Sleep 1 second (plus another half to be super duper safe) to make sure that
    // fakesystem.c gets a later timestamp than the fakesystem.o that was produced
    // during the build in setUp.  If we don't do this, there's a chance that the
    // ndk-build we run during the upcoming build will not rebuild it (on filesystems
    // that have 1-second granularity for last modified).
    // To verify this, create a Makefile with the following rule (don't forget to use a tab):
    // out: in
    //   cat $< > $@
    // Run: echo foo > in ; make ; cat out ; echo bar > in ; make ; cat out
    // On a filesystem with 1-second mtime granularity, the last "cat" should print "foo"
    // (with very high probability).
    Thread.sleep(1500);
    workspace.replaceFileContents(
        "native/fakenative/jni/fakesystem.c", "exit(status)", "exit(1+status)");

    workspace.resetBuildLogFile();
    workspace.buildAndReturnOutput(apkWithNdkLibrary);
    workspace.getBuildLog().assertTargetBuiltLocally(apkWithNdkLibrary);
  }

  @Test
  public void testEditingPrimaryDexClassForcesRebuildForSimplePackage() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    workspace.replaceFileContents(
        "java/com/sample/app/MyApplication.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    ProcessResult result = workspace.runBuckCommand("build", SIMPLE_TARGET);
    result.assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(SIMPLE_TARGET);
  }

  @Test
  public void testEditingSecondaryDexClassForcesRebuildForSimplePackage() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    workspace.replaceFileContents("java/com/sample/lib/Sample.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    ProcessResult result = workspace.runBuckCommand("build", SIMPLE_TARGET);
    result.assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(SIMPLE_TARGET);
  }

  @Test
  public void testNotAllJavaLibrariesFetched() throws IOException {
    String target = "//apps/multidex:app_with_deeper_deps";
    workspace.runBuckCommand("build", target).assertSuccess();
    workspace.replaceFileContents(
        "java/com/sample/app/MyApplication.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", target).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(target);
    buildLog.assertTargetIsAbsent("//java/com/sample/lib:lib");
  }

  @Test
  public void testProvidedDependenciesAreExcludedEvenIfSpecifiedInOtherDeps() throws IOException {
    String target = "//apps/sample:app_with_exported_and_provided_deps";
    ProcessResult result = workspace.runBuckBuild(target);
    result.assertSuccess();

    DexInspector dexInspector =
        new DexInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(target), "%s.apk")));

    dexInspector.assertTypeExists("Lcom/facebook/sample/Dep;");
    dexInspector.assertTypeExists("Lcom/facebook/sample/ExportedDep;");
    dexInspector.assertTypeDoesNotExist("Lcom/facebook/sample/ProvidedDep;");
    dexInspector.assertTypeDoesNotExist("Lcom/facebook/sample/DepProvidedDep;");
    dexInspector.assertTypeDoesNotExist("Lcom/facebook/sample/ExportedProvidedDep;");
  }

  @Test
  public void testPreprocessorForcesReDex() throws IOException {
    String target = "//java/com/preprocess:disassemble";
    Path outputFile = workspace.buildAndReturnOutput(target);
    String output = new String(Files.readAllBytes(outputFile), UTF_8);
    assertThat(output, containsString("content=2"));

    workspace.replaceFileContents("java/com/preprocess/convert.py", "content=2", "content=3");

    outputFile = workspace.buildAndReturnOutput(target);
    output = new String(Files.readAllBytes(outputFile), UTF_8);
    assertThat(output, containsString("content=3"));
  }

  @Test
  public void testDxFindsReferencedResources() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();
    BuildTarget dexTarget = BuildTargetFactory.newInstance("//java/com/sample/lib:lib#dex");
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpFolder.getRoot());
    Optional<String> resourcesFromMetadata =
        DexProducedFromJavaLibrary.readMetadataValue(
            filesystem, dexTarget, DexProducedFromJavaLibrary.REFERENCED_RESOURCES);
    assertTrue(resourcesFromMetadata.isPresent());
    assertEquals("[\"com.sample.top_layout\",\"com.sample2.title\"]", resourcesFromMetadata.get());
  }

  @Test
  public void testD8FindsReferencedResources() throws IOException {
    workspace.runBuckBuild(RES_D8_TARGET).assertSuccess();
    BuildTarget dexTarget = BuildTargetFactory.newInstance("//java/com/sample/lib:lib#d8");
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpFolder.getRoot());
    Optional<String> resourcesFromMetadata =
        DexProducedFromJavaLibrary.readMetadataValue(
            filesystem, dexTarget, DexProducedFromJavaLibrary.REFERENCED_RESOURCES);
    assertTrue(resourcesFromMetadata.isPresent());
    assertEquals("[\"com.sample.top_layout\",\"com.sample2.title\"]", resourcesFromMetadata.get());
  }

  @Test
  public void testDexingIsInputBased() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//java/com/sample/lib:lib#dex");

    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java", "import", "import /* no output change */");
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertNotTargetBuiltLocally("//java/com/sample/lib:lib#dex");
    buildLog.assertTargetHadMatchingInputRuleKey("//java/com/sample/lib:lib#dex");

    workspace.replaceFileContents(
        "java/com/sample/lib/Sample.java", "import", "import /* \n some output change */");
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();
    buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//java/com/sample/lib:lib#dex");
  }

  @Test
  public void testProguardDontObfuscateGeneratesMappingFile() throws IOException {
    String target = "//apps/sample:app_proguard_dontobfuscate";
    workspace.runBuckCommand("build", target).assertSuccess();

    Path mapping =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem, BuildTargetFactory.newInstance(target), "%s/proguard/mapping.txt"));
    assertTrue(Files.exists(mapping));
  }

  private static Path unzip(Path tmpDir, Path zipPath, String name) throws IOException {
    Path outPath = tmpDir.resolve(zipPath.getFileName());
    try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
      Files.copy(
          zipFile.getInputStream(zipFile.getEntry(name)),
          outPath,
          StandardCopyOption.REPLACE_EXISTING);
      return outPath;
    }
  }

  @Test
  public void testApksHaveDeterministicTimestamps() throws IOException {
    String target = "//apps/sample:app";
    ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    // Iterate over each of the entries, expecting to see all zeros in the time fields.
    Path apk =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
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
            BuildTargetPaths.getGenPath(
                filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
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
      if (rawTarget.contains("libgnustl_shared.so") || rawTarget.contains("libc___shared.so")) {
        // Stripping the C++ runtime is currently not shared.
        continue;
      }
      if (rawTarget.contains("strip")) {
        buildLog.assertNotTargetBuiltLocally(rawTarget);
      }
    }
  }

  @Test
  public void testSimpleD8App() throws IOException {
    workspace.runBuckBuild("//apps/sample:app_with_d8").assertSuccess();
  }

  @Test
  public void testD8AppWithMultidexContainsCanaryClasses() throws IOException {
    workspace.runBuckBuild("//apps/multidex:app_with_d8").assertSuccess();
    final Path path = workspace.buildAndReturnOutput("//apps/multidex:disassemble_app_with_d8");
    final List<String> smali = filesystem.readLines(path);
    assertFalse(smali.isEmpty());
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
  public void testNativeLibGeneratedProguardConfigIsUsedByProguardWithNdkPrior17()
      throws IOException {
    AssumeAndroidPlatform.assumeArmIsAvailable();
    String target = "//apps/sample:app_with_native_lib_proguard-16";
    workspace.runBuckBuild(target).assertSuccess();

    Path generatedConfig =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance(target)
                    .withFlavors(AndroidBinaryGraphEnhancer.NATIVE_LIBRARY_PROGUARD_FLAVOR),
                NativeLibraryProguardGenerator.OUTPUT_FORMAT));

    Path proguardDir =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem, BuildTargetFactory.newInstance(target), "%s/proguard"));

    Path proguardCommandLine = proguardDir.resolve("command-line.txt");
    // Check that the proguard command line references the native lib proguard config.
    assertTrue(workspace.getFileContents(proguardCommandLine).contains(generatedConfig.toString()));
    assertEquals(
        workspace.getFileContents("native/proguard_gen/expected-16.pro"),
        workspace.getFileContents(generatedConfig));
  }

  @Test
  public void testNativeLibGeneratedProguardConfigIsUsedByProguardWithNdkPrior18()
      throws IOException {
    AssumeAndroidPlatform.assumeGnuStlIsAvailable();
    String target = "//apps/sample:app_with_native_lib_proguard";
    workspace.runBuckBuild(target).assertSuccess();

    Path generatedConfig =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance(target)
                    .withFlavors(AndroidBinaryGraphEnhancer.NATIVE_LIBRARY_PROGUARD_FLAVOR),
                NativeLibraryProguardGenerator.OUTPUT_FORMAT));

    Path proguardDir =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem, BuildTargetFactory.newInstance(target), "%s/proguard"));

    Path proguardCommandLine = proguardDir.resolve("command-line.txt");
    // Check that the proguard command line references the native lib proguard config.
    assertTrue(workspace.getFileContents(proguardCommandLine).contains(generatedConfig.toString()));
    assertEquals(
        workspace.getFileContents("native/proguard_gen/expected-17.pro"),
        workspace.getFileContents(generatedConfig));
  }

  @Test
  public void testNativeLibGeneratedProguardConfigIsUsedByProguard() throws IOException {
    AssumeAndroidPlatform.assumeGnuStlIsNotAvailable();
    String target = "//apps/sample:app_with_native_lib_proguard";
    workspace.runBuckBuild(target).assertSuccess();

    Path generatedConfig =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem,
                BuildTargetFactory.newInstance(target)
                    .withFlavors(AndroidBinaryGraphEnhancer.NATIVE_LIBRARY_PROGUARD_FLAVOR),
                NativeLibraryProguardGenerator.OUTPUT_FORMAT));

    Path proguardDir =
        workspace.getPath(
            BuildTargetPaths.getGenPath(
                filesystem, BuildTargetFactory.newInstance(target), "%s/proguard"));

    Path proguardCommandLine = proguardDir.resolve("command-line.txt");
    // Check that the proguard command line references the native lib proguard config.
    assertTrue(workspace.getFileContents(proguardCommandLine).contains(generatedConfig.toString()));
    assertEquals(
        workspace.getFileContents("native/proguard_gen/expected.pro"),
        workspace.getFileContents(generatedConfig));
  }

  @Test
  public void testReDexIsCalledAppropriatelyFromAndroidBinary() throws IOException {
    Path apk = workspace.buildAndReturnOutput(APP_REDEX_TARGET);
    Path unzippedApk = unzip(apk.getParent(), apk, "app_redex");

    // We use a fake ReDex binary that writes out the arguments it received as JSON so that we can
    // verify that it was called in the right way.
    @SuppressWarnings("unchecked")
    Map<String, Object> userData = ObjectMappers.readValue(unzippedApk, Map.class);

    String androidSdk = (String) userData.get("ANDROID_SDK");
    assertTrue(
        "ANDROID_SDK environment variable must be set so ReDex runs with zipalign",
        androidSdk != null && !androidSdk.isEmpty());
    assertEquals(workspace.getDestPath().toString(), userData.get("PWD"));

    assertTrue(userData.get("config").toString().endsWith("apps/sample/redex-config.json"));
    assertEquals("buck-out/gen/apps/sample/app_redex/proguard/seeds.txt", userData.get("keep"));
    assertEquals("my_alias", userData.get("keyalias"));
    assertEquals("android", userData.get("keypass"));
    assertEquals(
        workspace.resolve("keystores/debug.keystore").toString(), userData.get("keystore"));
    assertEquals(
        "buck-out/gen/apps/sample/app_redex__redex/app_redex.redex.apk", userData.get("out"));
    assertEquals("buck-out/gen/apps/sample/app_redex/proguard/command-line.txt", userData.get("P"));
    assertEquals(
        "buck-out/gen/apps/sample/app_redex/proguard/mapping.txt", userData.get("proguard-map"));
    assertTrue((Boolean) userData.get("sign"));
    assertEquals("my_param_name={\"foo\": true}", userData.get("J"));
    assertTrue(
        "redex_extra_args: -j $(location ...) is not properly expanded!",
        userData.get("j").toString().endsWith(".jar"));
    assertTrue(
        "redex_extra_args: -S $(location ...) is not properly expanded!",
        userData.get("S").toString().contains("coldstart_classes=")
            && !userData.get("S").toString().contains("location"));
  }

  @Test
  public void testEditingRedexToolForcesRebuild() throws IOException {
    workspace.runBuckBuild(APP_REDEX_TARGET).assertSuccess();
    workspace.replaceFileContents("tools/redex/fake_redex.py", "main()\n", "main() \n");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(APP_REDEX_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(APP_REDEX_TARGET);
  }

  @Test
  public void testEditingSecondaryDexHeadListForcesRebuild() throws IOException {
    workspace.runBuckBuild(APP_REDEX_TARGET).assertSuccess();
    workspace.replaceFileContents("tools/redex/secondary_dex_head.list", "", " ");

    workspace.resetBuildLogFile();
    workspace.runBuckBuild(APP_REDEX_TARGET).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(APP_REDEX_TARGET);
  }

  @Test
  public void testInstrumentationApkWithEmptyResDepBuildsCorrectly() throws IOException {
    workspace.runBuckBuild("//apps/sample:instrumentation_apk").assertSuccess();
  }

  @Test
  public void testInvalidKeystoreKeyAlias() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    workspace.replaceFileContents(
        "keystores/debug.keystore.properties", "key.alias=my_alias", "key.alias=invalid_alias");

    workspace.resetBuildLogFile();
    ProcessResult result = workspace.runBuckCommand("build", SIMPLE_TARGET);
    result.assertFailure("Invalid keystore key alias should fail.");

    assertThat(
        "error message for invalid keystore key alias is incorrect.",
        result.getStderr(),
        containsRegex("The keystore \\[.*\\] key\\.alias \\[.*\\].*does not exist"));
  }

  @Test
  public void testManifestMerge() throws IOException {
    Path mergedPath = workspace.buildAndReturnOutput("//manifests:manifest");
    String contents = workspace.getFileContents(mergedPath);

    Pattern readCalendar =
        Pattern.compile(
            "<uses-permission-sdk-23 android:name=\"android\\.permission\\.READ_CALENDAR\" />");
    int matchCount = 0;
    Matcher matcher = readCalendar.matcher(contents);
    while (matcher.find()) {
      matchCount++;
    }
    assertEquals(
        String.format(
            "Expected one uses-permission-sdk-23=READ_CALENDAR tag, but found %d: %s",
            matchCount, contents),
        1,
        matchCount);
  }

  @Test
  public void testAutomaticManifestMerge() throws IOException {
    Path dumpPath = workspace.buildAndReturnOutput("//apps/sample:dump_merged_manifest");
    String contents = workspace.getFileContents(dumpPath);

    assertThat(contents, containsString("READ_CALENDAR"));
  }

  @Test
  public void testErrorReportingDuringManifestMerging() throws IOException {
    ProcessResult processResult =
        workspace.runBuckBuild("//apps/sample:dump_invalid_merged_manifest");
    assertThat(
        processResult.getStderr(),
        containsString(
            "The prefix \"tools\" for attribute \"tools:targetAPI\" "
                + "associated with an element type \"intent-filter\" is not bound."));
  }

  @Test
  public void testProguardOutput() throws IOException {
    ImmutableMap<String, Path> outputs =
        workspace.buildMultipleAndReturnOutputs(
            "//apps/sample:proguard_output_dontobfuscate",
            "//apps/sample:proguard_output_dontobfuscate_no_aapt");

    String withAapt =
        workspace.getFileContents(outputs.get("//apps/sample:proguard_output_dontobfuscate"));
    String withoutAapt =
        workspace.getFileContents(
            outputs.get("//apps/sample:proguard_output_dontobfuscate_no_aapt"));

    assertThat(withAapt, containsString("-printmapping"));
    assertThat(withAapt, containsString("#generated"));
    assertThat(withoutAapt, containsString("-printmapping"));
    assertThat(withoutAapt, CoreMatchers.not(containsString("#generated")));
  }

  @Test
  public void testSimpleApkSignature() throws IOException {
    Path apkPath = workspace.buildAndReturnOutput(SIMPLE_TARGET);
    File apkFile = filesystem.getPathForRelativePath(apkPath).toFile();
    ApkVerifier.Builder apkVerifierBuilder = new ApkVerifier.Builder(apkFile);
    ApkVerifier.Result result;
    try {
      result = apkVerifierBuilder.build().verify();
    } catch (Exception e) {
      throw new IOException("Failed to determine APK's minimum supported platform version", e);
    }
    assertTrue(result.isVerifiedUsingV1Scheme());
    assertTrue(result.isVerifiedUsingV2Scheme());
  }

  @Test
  public void testClasspathQueryFunctionWorksOnAndroidBinary() throws IOException {
    Path output = workspace.buildAndReturnOutput("//apps/sample:dump_classpath");
    String[] actualClasspath = workspace.getFileContents(output).split("\\s+");
    assertThat(
        actualClasspath,
        Matchers.array(
            Matchers.containsString("//apps/sample:app"),
            Matchers.containsString("//java/com/sample/lib:lib")));
  }
}
