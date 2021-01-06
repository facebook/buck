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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.jvm.java.testutil.AbiCompilationModeTest;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AndroidPrebuiltAarIntegrationTest extends AbiCompilationModeTest {

  private ProjectWorkspace workspace;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Before
  public void setUp() throws IOException {
    workspace = TestDataHelper.createProjectWorkspaceForScenario(this, "android_prebuilt_aar", tmp);
    workspace.setUp();
    AssumeAndroidPlatform.get(workspace).assumeSdkIsAvailable();
    setWorkspaceCompilationMode(workspace);
  }

  @Test
  public void thatAndroidToolchainIsNotRequired() throws IOException {
    // It's kind of dumb that we enforce this, but it makes our lives easier.
    String badSdkPath = tmp.getRoot().resolve("some_non_existent_path").toString();
    workspace.addBuckConfigLocalOption("android", "sdk_path", badSdkPath);
    workspace
        .runBuckCommandWithEnvironmentOverridesAndContext(
            tmp.getRoot(),
            Optional.empty(),
            ImmutableMap.of("ANDROID_SDK", badSdkPath, "ANDROID_HOME", badSdkPath, "ANDROID_SDK_ROOT", badSdkPath),
            "targets",
            "--show-rulekey",
            "//:aar")
        .assertSuccess();
  }

  @Test
  public void testBuildAndroidPrebuiltAar() throws IOException {
    String target = "//:app";
    workspace.runBuckBuild(target).assertSuccess();
    ZipInspector zipInspector =
        new ZipInspector(
            workspace.getPath(
                BuildTargetPaths.getGenPath(
                    workspace.getProjectFileSystem(),
                    BuildTargetFactory.newInstance(target),
                    "%s.apk")));
    zipInspector.assertFileExists("AndroidManifest.xml");
    zipInspector.assertFileExists("resources.arsc");
    zipInspector.assertFileExists("classes.dex");
    zipInspector.assertFileExists("lib/x86/liba.so");
  }

  @Test
  public void testPrebuiltJarInDepsIsExported() {
    workspace.runBuckBuild("//prebuilt_jar-dep:lib").assertSuccess();
  }

  @Test
  public void testAndroidPrebuiltAarInDepsIsExported() {
    workspace.runBuckBuild("//android_prebuilt_aar-dep:lib").assertSuccess();
  }

  @Test
  public void testPrebuiltRDotTxtContainsTransitiveDependencies() throws IOException {
    String target = "//third-party/design-library:design-library";
    workspace.runBuckBuild(target).assertSuccess();

    String appCompatResource = "TextAppearance_AppCompat_Body2";

    String rDotTxt =
        workspace.getFileContents(
            BuildTargetPaths.getScratchPath(
                workspace.getProjectFileSystem(),
                BuildTargetFactory.newInstance(target)
                    .withFlavors(AndroidPrebuiltAarDescription.AAR_UNZIP_FLAVOR),
                "__unpack_%s__/R.txt"));
    assertThat(
        "R.txt contains transitive dependencies", rDotTxt, containsString(appCompatResource));
  }

  @Test
  public void testExtraDepsDontResultInWarning() {
    ProcessResult result = workspace.runBuckBuild("//:app-extra-res-entry").assertSuccess();

    String buildOutput = result.getStderr();
    assertThat("No warnings are shown", buildOutput, not(containsString("Cannot find resource")));
  }

  @Test
  public void testNoClassesDotJar() {
    workspace.runBuckBuild("//:app-no-classes-dot-jar").assertSuccess();
  }

  @Test
  public void testAarWithoutResBuildsFine() {
    workspace.runBuckBuild("//:app-dep-on-aar-without-res").assertSuccess();
  }

  @Test
  public void testAarWhichNeedsToSupportNativeLoaderIsPackagedInLibsDir() throws Exception {
    // Check that a vanilla APK will package the libs in the "standard" place
    Path outputApk = workspace.buildAndReturnOutput("//:app-dep-on-aar-with-native-loaded-so");
    ZipInspector zipInspector = new ZipInspector(outputApk);
    zipInspector.assertFileExists("lib/armeabi-v7a/libdep.so");
    zipInspector.assertFileExists("lib/x86/libdep.so");
    // We also have another lib: liba from a different dep, make sure it gets packaged properly
    zipInspector.assertFileExists("lib/x86/liba.so");
  }

  @Test
  public void testAarWhichNeedsToSupportNativeLoaderDoesNotGetExopackage() throws IOException {
    // In an exopackage build, the native lib needs to go into the APK even when the other native
    // libs are exo-loaded
    Path outputApk =
        workspace.buildAndReturnOutput("//:app-dep-on-aar-with-native-loaded-so_exo-native");
    ZipInspector zipInspector = new ZipInspector(outputApk);
    zipInspector.assertFileExists("lib/armeabi-v7a/libdep.so");
    zipInspector.assertFileExists("lib/x86/libdep.so");

    Path exoSymlinkTree =
        workspace.buildAndReturnOutput(
            "//:app-dep-on-aar-with-native-loaded-so_exo-native#exo_symlink_tree");

    List<String> metadataContents =
        Files.readAllLines(
                exoSymlinkTree.resolve("native-libs").resolve("x86").resolve("metadata.txt"))
            .stream()
            .map(line -> line.split("\\s+")[0])
            .sorted()
            .collect(Collectors.toList());

    // The exo-dir should contain the other libs
    assertTrue(metadataContents.contains("liba"));
    // but not the one that was excluded via the native_loader flag
    assertFalse(metadataContents.contains("libdep"));
  }

  @Test
  public void testIfAllNativeDepsAreSupportSystemThenDoNotCopyNativeLibs() throws IOException {
    Path outputApk = workspace.buildAndReturnOutput("//:app-system-loader-aar-only");
    ZipInspector zipInspector = new ZipInspector(outputApk);
    zipInspector.assertFileExists("lib/x86/libdep.so");
  }

  @Test
  public void testSystemLoadedLibsRespectCpuFilter() throws IOException {
    Path outputApk =
        workspace.buildAndReturnOutput("//:app-dep-on-aar-with-native-loaded-so_exo-native_armv7");
    ZipInspector zipInspector = new ZipInspector(outputApk);
    zipInspector.assertFileExists("lib/armeabi-v7a/libdep.so");
    // Since we have a cpu filter for armv7 we should _not_ see x86 deps
    zipInspector.assertFileDoesNotExist("lib/x86/libdep.so");
  }
}
