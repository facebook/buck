/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.DefaultPropertyFinder;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.VersionStringComparator;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

public class AndroidResourceFilterIntegrationTest {

  private static boolean isBuildToolsNew;
  private static Path pathToAapt;

  private static final String APK_PATH_FORMAT = "buck-out/gen/apps/sample/%s.apk";

  @Rule
  public DebuggableTemporaryFolder tmpFolder = new DebuggableTemporaryFolder();

  private ProjectWorkspace workspace;

  @BeforeClass
  public static void findBuildToolsVersion() {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectFilesystem filesystem = new ProjectFilesystem(Paths.get("."));
    AndroidDirectoryResolver resolver = new DefaultAndroidDirectoryResolver(
        filesystem,
        Optional.<String>absent(),
        new DefaultPropertyFinder(filesystem, ImmutableMap.copyOf(System.getenv())));
    pathToAapt = AndroidPlatformTarget.getDefaultPlatformTarget(
        resolver,
        Optional.<Path>absent()).getAaptExecutable();
    String buildToolsVersion = pathToAapt.getParent().getFileName().toString();
    isBuildToolsNew = new VersionStringComparator().compare(buildToolsVersion, "21") >= 0;
  }

  @Before
  public void setUp() throws IOException {
    tmpFolder.create();
    workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "android_project", tmpFolder);
    workspace.setUp();
  }

  @Test
  public void testApkWithoutResourceFilter() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", "//apps/sample:app");
    result.assertSuccess();

    File apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    if (isBuildToolsNew) {
      zipInspector.assertFileExists("res/drawable-mdpi-v4/app_icon.png");
      zipInspector.assertFileExists("res/drawable-hdpi-v4/app_icon.png");
      zipInspector.assertFileExists("res/drawable-xhdpi-v4/app_icon.png");
    } else {
      zipInspector.assertFileExists("res/drawable-mdpi/app_icon.png");
      zipInspector.assertFileExists("res/drawable-hdpi/app_icon.png");
      zipInspector.assertFileExists("res/drawable-xhdpi/app_icon.png");
    }
  }

  @Test
  public void testApkWithMdpiFilter() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", "//apps/sample:app_mdpi");
    result.assertSuccess();

    File apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app_mdpi"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    if (isBuildToolsNew) {
      zipInspector.assertFileExists("res/drawable-mdpi-v4/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-hdpi-v4/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-xhdpi-v4/app_icon.png");
    } else {
      zipInspector.assertFileExists("res/drawable-mdpi/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-hdpi/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-xhdpi/app_icon.png");
    }
  }

  @Test
  public void testModifyingImageRebuildsResourcesFilter() throws IOException {
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild("//apps/sample:app_mdpi");
    result.assertSuccess();

    File apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app_mdpi"));
    String iconPath = isBuildToolsNew
        ? "res/drawable-mdpi-v4/app_icon.png"
        : "res/drawable-mdpi/app_icon.png";
    long firstImageCrc = new ZipInspector(apkFile).getCrc(iconPath);

    workspace.copyFile(
        "res/com/sample/base/res/drawable-hdpi/app_icon.png",
        "res/com/sample/base/res/drawable-mdpi/app_icon.png");

    workspace.resetBuildLogFile();
    result = workspace.runBuckBuild("//apps/sample:app_mdpi");
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally("//apps/sample:app_mdpi");

    apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app_mdpi"));
    long secondImageCrc = new ZipInspector(apkFile).getCrc(iconPath);

    assertNotEquals(firstImageCrc, secondImageCrc);
  }

  @Test
  public void testApkWithXhdpiAndHdpiFilter() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", "//apps/sample:app_hdpi_xhdpi");
    result.assertSuccess();

    File apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app_hdpi_xhdpi"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    if (isBuildToolsNew) {
      zipInspector.assertFileDoesNotExist("res/drawable-mdpi-v4/app_icon.png");
      zipInspector.assertFileExists("res/drawable-hdpi-v4/app_icon.png");
      zipInspector.assertFileExists("res/drawable-xhdpi-v4/app_icon.png");
    } else {
      zipInspector.assertFileDoesNotExist("res/drawable-mdpi/app_icon.png");
      zipInspector.assertFileExists("res/drawable-hdpi/app_icon.png");
      zipInspector.assertFileExists("res/drawable-xhdpi/app_icon.png");
    }
  }

  @Test
  public void testApkWithStringsAsAssets() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckCommand("build", "//apps/sample:app_comp_str");
    result.assertSuccess();

    File apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app_comp_str"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    zipInspector.assertFileExists("assets/strings/fr.fbstr");
  }

  @Test
  public void testStringArtifactsAreCached() throws IOException {
    workspace.enableDirCache();
    workspace.runBuckBuild("//apps/sample:app_comp_str").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    Sha1HashCode androidBinaryRuleKey = buildLog.getRuleKey("//apps/sample:app_comp_str");
    File cachedFile = workspace.getFile("buck-cache/" + androidBinaryRuleKey.getHash());
    assertTrue(cachedFile.delete());

    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckBuild("//apps/sample:app_comp_str").assertSuccess();
  }

  @Test
  public void testApkWithStringsAsAssetsAndResourceFilter() throws IOException {
    ProjectWorkspace.ProcessResult result =
        workspace.runBuckBuild("//apps/sample:app_comp_str_xhdpi");
    result.assertSuccess();

    File apkFile = workspace.getFile(String.format(APK_PATH_FORMAT, "app_comp_str_xhdpi"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    zipInspector.assertFileExists("assets/strings/fr.fbstr");

    if (isBuildToolsNew) {
      zipInspector.assertFileExists("res/drawable-xhdpi-v4/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-hdpi-v4/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-mdpi-v4/app_icon.png");
    } else {
      zipInspector.assertFileExists("res/drawable-xhdpi/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-hdpi/app_icon.png");
      zipInspector.assertFileDoesNotExist("res/drawable-mdpi/app_icon.png");
    }
  }

  @Test
  public void testAsset() throws IOException {
    workspace.enableDirCache();
    workspace.runBuckBuild("//apps/sample:app").assertSuccess();
    String apkFilePath = String.format(APK_PATH_FORMAT, "app");

    File apkFile = workspace.getFile(apkFilePath);
    ZipInspector zipInspector = new ZipInspector(apkFile);

    long firstCrc = zipInspector.getCrc("assets/asset_file.txt");

    workspace.replaceFileContents(
        "res/com/sample/asset_only/assets/asset_file.txt",
        "Hello",
        "Bye");
    workspace.runBuckBuild("//apps/sample:app").assertSuccess();

    apkFile = workspace.getFile(apkFilePath);
    zipInspector = new ZipInspector(apkFile);

    long secondCrc = zipInspector.getCrc("assets/asset_file.txt");

    assertNotEquals("Rebuilt APK file must include the new asset file.", firstCrc, secondCrc);
  }

  @Test
  public void testEnglishBuildDoesntContainFrenchStrings()
      throws IOException, InterruptedException {
    workspace.runBuckBuild("//apps/sample:app").assertSuccess();
    String apkFilePath = String.format(APK_PATH_FORMAT, "app");
    File apkFile = workspace.getFile(apkFilePath);

    int matchingLines = runAaptDumpResources(apkFile);
    assertEquals(2, matchingLines);

    workspace.runBuckBuild("//apps/sample:app_en").assertSuccess();
    apkFilePath = String.format(APK_PATH_FORMAT, "app_en");
    apkFile = workspace.getFile(apkFilePath);

    matchingLines = runAaptDumpResources(apkFile);
    assertEquals(1, matchingLines);
  }

  private int runAaptDumpResources(File apkFile) throws IOException, InterruptedException {
    final Pattern pattern = Pattern.compile(".*com.example:string/base_button: t=.*");
    ProcessExecutor.Result result = workspace.runCommand(
        pathToAapt.toAbsolutePath().toString(),
        "dump",
        "resources",
        apkFile.getAbsolutePath());
    assertEquals(0, result.getExitCode());
    return FluentIterable.from(
        Splitter.on('\n').split(result.getStdout().or(""))).filter(
        new Predicate<String>() {
          @Override
          public boolean apply(String input) {
            return pattern.matcher(input).matches();
          }
        }).size();
  }
}
