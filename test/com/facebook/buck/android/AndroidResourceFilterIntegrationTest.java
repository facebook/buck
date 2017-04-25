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

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.DirArtifactCacheTestUtil;
import com.facebook.buck.artifact_cache.TestArtifactCaches;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.testutil.integration.ZipInspector;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.VersionStringComparator;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class AndroidResourceFilterIntegrationTest {

  private static boolean isBuildToolsNew;
  private static Path pathToAapt;

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths();

  private ProjectWorkspace workspace;

  private ProjectFilesystem filesystem;

  @BeforeClass
  public static void findBuildToolsVersion() throws InterruptedException {
    AssumeAndroidPlatform.assumeSdkIsAvailable();
    ProjectFilesystem filesystem = new ProjectFilesystem(Paths.get(".").toAbsolutePath());
    AndroidDirectoryResolver resolver =
        new DefaultAndroidDirectoryResolver(
            filesystem.getRootPath().getFileSystem(),
            ImmutableMap.copyOf(System.getenv()),
            Optional.empty(),
            Optional.empty());
    pathToAapt =
        AndroidPlatformTarget.getDefaultPlatformTarget(resolver, Optional.empty(), Optional.empty())
            .getAaptExecutable();
    String buildToolsVersion = pathToAapt.getParent().getFileName().toString();
    isBuildToolsNew = new VersionStringComparator().compare(buildToolsVersion, "21") >= 0;
  }

  @Before
  public void setUp() throws InterruptedException, IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "android_project", tmpFolder);
    workspace.setUp();
    filesystem = new ProjectFilesystem(workspace.getDestPath());
  }

  @Test
  public void testApkWithoutResourceFilter() throws IOException {
    String target = "//apps/sample:app";
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
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
    String target = "//apps/sample:app_mdpi";
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
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
    String target = "//apps/sample:app_mdpi";
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild(target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
    String iconPath =
        isBuildToolsNew ? "res/drawable-mdpi-v4/app_icon.png" : "res/drawable-mdpi/app_icon.png";
    long firstImageCrc = new ZipInspector(apkFile).getCrc(iconPath);

    workspace.copyFile(
        "res/com/sample/base/res/drawable-hdpi/app_icon.png",
        "res/com/sample/base/res/drawable-mdpi/app_icon.png");

    workspace.resetBuildLogFile();
    result = workspace.runBuckBuild(target);
    result.assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();
    buildLog.assertTargetBuiltLocally(target);

    apkFile =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
    long secondImageCrc = new ZipInspector(apkFile).getCrc(iconPath);

    assertNotEquals(firstImageCrc, secondImageCrc);
  }

  @Test
  public void testApkWithXhdpiAndHdpiFilter() throws IOException {
    String target = "//apps/sample:app_hdpi_xhdpi";
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
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
    String target = "//apps/sample:app_comp_str";
    ProjectWorkspace.ProcessResult result = workspace.runBuckCommand("build", target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    zipInspector.assertFileExists("assets/strings/fr.fbstr");
  }

  @Test
  public void testStringArtifactsAreCached() throws InterruptedException, IOException {
    Assume.assumeFalse(true);
    workspace.enableDirCache();
    workspace.runBuckBuild("//apps/sample:app_comp_str").assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    Sha1HashCode androidBinaryRuleKey = buildLog.getRuleKey("//apps/sample:app_comp_str");

    ArtifactCache cache =
        TestArtifactCaches.createDirCacheForTest(
            workspace.getPath("."), filesystem.getBuckPaths().getCacheDir());
    Path cachedFile =
        DirArtifactCacheTestUtil.getPathForRuleKey(
            cache, new RuleKey(androidBinaryRuleKey.getHash()), Optional.empty());
    Files.delete(workspace.resolve(cachedFile));

    workspace.runBuckCommand("clean").assertSuccess();
    workspace.runBuckBuild("//apps/sample:app_comp_str").assertSuccess();
  }

  @Test
  public void testApkWithStringsAsAssetsAndResourceFilter() throws IOException {
    String target = "//apps/sample:app_comp_str_xhdpi";
    ProjectWorkspace.ProcessResult result = workspace.runBuckBuild(target);
    result.assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
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
    String target = "//apps/sample:app";
    workspace.runBuckBuild(target).assertSuccess();

    Path apkFile =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
    ZipInspector zipInspector = new ZipInspector(apkFile);

    long firstCrc = zipInspector.getCrc("assets/asset_file.txt");

    workspace.replaceFileContents(
        "res/com/sample/asset_only/assets/asset_file.txt", "Hello", "Bye");
    workspace.runBuckBuild(target).assertSuccess();

    apkFile =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));
    zipInspector = new ZipInspector(apkFile);

    long secondCrc = zipInspector.getCrc("assets/asset_file.txt");

    assertNotEquals("Rebuilt APK file must include the new asset file.", firstCrc, secondCrc);
  }

  @Test
  public void testEnglishBuildDoesntContainFrenchStrings()
      throws IOException, InterruptedException {
    String target = "//apps/sample:app";
    workspace.runBuckBuild(target).assertSuccess();
    Path apkFile =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));

    int matchingLines = runAaptDumpResources(apkFile);
    assertEquals(2, matchingLines);

    target = "//apps/sample:app_en";
    workspace.runBuckBuild(target).assertSuccess();
    apkFile =
        workspace.getPath(
            BuildTargets.getGenPath(filesystem, BuildTargetFactory.newInstance(target), "%s.apk"));

    matchingLines = runAaptDumpResources(apkFile);
    assertEquals(1, matchingLines);
  }

  private int runAaptDumpResources(Path apkFile) throws IOException, InterruptedException {
    final Pattern pattern = Pattern.compile(".*com.example:string/base_button: t=.*");
    ProcessExecutor.Result result =
        workspace.runCommand(
            pathToAapt.toAbsolutePath().toString(),
            "dump",
            "resources",
            apkFile.toAbsolutePath().toString());
    assertEquals(0, result.getExitCode());
    return FluentIterable.from(Splitter.on('\n').split(result.getStdout().orElse("")))
        .filter(input -> pattern.matcher(input).matches())
        .size();
  }
}
