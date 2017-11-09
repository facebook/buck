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

import static com.facebook.buck.testutil.RegexMatcher.containsRegex;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DexInspector;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ObjectMappers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidBinary2IntegrationTest extends AbiCompilationModeTest {

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths(true);

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  private static final String SIMPLE_TARGET = "//apps/multidex:app";
  private static final String APP_REDEX_TARGET = "//apps/sample:app_redex";

  @Before
  public void setUp() throws InterruptedException, IOException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    AssumeAndroidPlatform.assumeNdkIsAvailable();
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            new AndroidBinary2IntegrationTest(), "android_project", tmpFolder);
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
                BuildTargets.getGenPath(
                    filesystem, BuildTargetFactory.newInstance(SIMPLE_TARGET), "%s.apk")));

    zipInspector.assertFileExists("assets/secondary-program-dex-jars/metadata.txt");
    zipInspector.assertFileExists("assets/secondary-program-dex-jars/secondary-1.dex.jar");
    zipInspector.assertFileDoesNotExist("classes2.dex");

    zipInspector.assertFileExists("classes.dex");
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
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
    zipInspector.assertFileExists("lib/armeabi/libnative_cxx_lib.so");
  }

  @Test
  public void testDisguisedExecutableIsRenamed() throws IOException {
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_disguised_exe");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi/libmybinary.so");
  }

  @Test
  public void testNdkLibraryIsIncluded() throws IOException {
    Path output = workspace.buildAndReturnOutput("//apps/sample:app_with_ndk_library");
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi/libfakenative.so");
  }

  @Test
  public void testEditingNdkLibraryForcesRebuild() throws IOException, InterruptedException {
    String apkWithNdkLibrary = "//apps/sample:app_with_ndk_library";
    Path output = workspace.buildAndReturnOutput(apkWithNdkLibrary);
    ZipInspector zipInspector = new ZipInspector(output);
    zipInspector.assertFileExists("lib/armeabi/libfakenative.so");

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
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", SIMPLE_TARGET);
    result.assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();

    buildLog.assertTargetBuiltLocally(SIMPLE_TARGET);
  }

  @Test
  public void testEditingSecondaryDexClassForcesRebuildForSimplePackage() throws IOException {
    workspace.runBuckBuild(SIMPLE_TARGET).assertSuccess();

    workspace.replaceFileContents("java/com/sample/lib/Sample.java", "package com", "package\ncom");

    workspace.resetBuildLogFile();
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", SIMPLE_TARGET);
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
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild(target);
    result.assertSuccess();

    DexInspector dexInspector =
        new DexInspector(
            workspace.getPath(
                BuildTargets.getGenPath(
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
  public void testDxFindsReferencedResources() throws InterruptedException, IOException {
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
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", SIMPLE_TARGET);
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
}
