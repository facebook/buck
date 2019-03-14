/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.android.exopackage.ExopackageSymlinkTree;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Check to see if an exopackage symlink tree is needed. The tree lays out the files which need to
 * be populated into the app's exopackage directory so that the test runner can adb push them to the
 * device.
 */
class ExopackageSymlinkTreeStep implements Step {

  private final HasInstallableApk apk;
  private final Optional<HasInstallableApk> maybeApkUnderTest;
  private final BuildContext buildContext;

  public ExopackageSymlinkTreeStep(
      HasInstallableApk apk,
      Optional<HasInstallableApk> maybeApkUnderTest,
      BuildContext buildContext) {
    this.apk = apk;
    this.maybeApkUnderTest = maybeApkUnderTest;
    this.buildContext = buildContext;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    executeStep();
    return StepExecutionResult.of(0);
  }

  @Override
  public String getShortName() {
    return "exopackage_symlink_tree";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Set up exopackage for the external test runner";
  }

  public void executeStep() {
    SourcePathResolver pathResolver = buildContext.getSourcePathResolver();
    // First check our APK to see if exopackage setup is required
    if (ExopackageInstaller.exopackageEnabled(apk.getApkInfo())) {
      createExopackageDirForInstallable(apk, pathResolver);
    }

    // If this is an InstrumentationApk, the apk-under-test may also require exo-install
    if (maybeApkUnderTest
        .map(HasInstallableApk::getApkInfo)
        .map(ExopackageInstaller::exopackageEnabled)
        .orElse(false)) {
      createExopackageDirForInstallable(maybeApkUnderTest.get(), pathResolver);
    }
  }

  /**
   * Lay out a directory tree which is an exact mirror of how the exopackage items should appear on
   * the device with symlinks to the actual items. The root of the dir will contain a metadata.txt
   * containing the full path to the root of the folder on the device.
   *
   * @param installable the installable rule which may have exopackage items
   * @param pathResolver used to find the exo info files
   */
  private void createExopackageDirForInstallable(
      HasInstallableApk installable, SourcePathResolver pathResolver) {
    installable
        .getApkInfo()
        .getExopackageInfo()
        .ifPresent(
            exoInfo -> {
              // Set up a scratch path where we can lay out a symlink tree
              ProjectFilesystem filesystem = installable.getProjectFilesystem();
              Path exopackageSymlinkTreePath =
                  getExopackageSymlinkTreePath(installable.getBuildTarget(), filesystem);
              String packageName =
                  AdbHelper.tryToExtractPackageNameFromManifest(pathResolver, apk.getApkInfo());
              try {
                filesystem.deleteRecursivelyIfExists(exopackageSymlinkTreePath);
                filesystem.mkdirs(exopackageSymlinkTreePath);
                // Create a symlink tree which lays out files exactly how they should be pushed to
                // the device
                ExopackageSymlinkTree.createSymlinkTree(
                    packageName, exoInfo, pathResolver, filesystem, exopackageSymlinkTreePath);
              } catch (IOException e) {
                throw new HumanReadableException(
                    e,
                    "Unable to install %s, could not set up symlink tree",
                    installable.getBuildTarget());
              }
            });
  }

  /**
   * @return the temporary directory where we lay out the exopackage symlink tree so the external
   *     testrunner can find all the components
   */
  static Path getExopackageSymlinkTreePath(BuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargetPaths.getScratchPath(filesystem, target, "__%s__exopackage_dir__");
  }
}
