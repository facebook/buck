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

import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.android.toolchain.ndk.impl.AndroidNdkHelper;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.util.VersionStringComparator;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;

public class AssumeAndroidPlatform {
  private static final VersionStringComparator VERSION_STRING_COMPARATOR =
      new VersionStringComparator();

  private final ProjectFilesystem projectFilesystem;
  private final Optional<AndroidSdkResolver> sdkResolver;

  private AssumeAndroidPlatform(ProjectFilesystem projectFilesystem) throws IOException {
    this.projectFilesystem = projectFilesystem;
    this.sdkResolver = AndroidSdkResolver.get(projectFilesystem);
  }

  public static AssumeAndroidPlatform get(ProjectFilesystem projectFilesystem) throws IOException {
    return new AssumeAndroidPlatform(projectFilesystem);
  }

  public static AssumeAndroidPlatform get(ProjectWorkspace workspace) throws IOException {
    return get(workspace.getProjectFileSystem());
  }

  public static AssumeAndroidPlatform getForDefaultFilesystem() throws IOException {
    return new AssumeAndroidPlatform(
        TestProjectFilesystems.createProjectFilesystem(Paths.get(".").toAbsolutePath()));
  }

  public void assumeNdkIsAvailable() {
    Optional<AndroidNdk> androidNdk = AndroidNdkHelper.detectAndroidNdk(projectFilesystem);
    assumeTrue(androidNdk.isPresent());
  }

  public void assumeArmIsAvailable() {
    assumeTrue(isArmAvailable());
  }

  public boolean isArmAvailable() {
    Optional<AndroidNdk> androidNdk = AndroidNdkHelper.detectAndroidNdk(projectFilesystem);
    if (!androidNdk.isPresent()) {
      return false;
    }
    return VERSION_STRING_COMPARATOR.compare(androidNdk.get().getNdkVersion(), "17") < 0;
  }

  public void assumeGnuStlIsAvailable() {
    assumeTrue(isGnuStlAvailable());
  }

  public void assumeGnuStlIsNotAvailable() {
    assumeFalse(isGnuStlAvailable());
  }

  public boolean isGnuStlAvailable() {
    Optional<AndroidNdk> androidNdk = AndroidNdkHelper.detectAndroidNdk(projectFilesystem);
    if (!androidNdk.isPresent()) {
      return false;
    }

    return VERSION_STRING_COMPARATOR.compare(androidNdk.get().getNdkVersion(), "18") < 0;
  }

  public void assumeUnifiedHeadersAvailable() {
    assumeTrue(isUnifiedHeadersAvailable());
  }

  public boolean isUnifiedHeadersAvailable() {
    Optional<AndroidNdk> androidNdk = AndroidNdkHelper.detectAndroidNdk(projectFilesystem);
    assumeTrue(androidNdk.isPresent());
    return VERSION_STRING_COMPARATOR.compare(androidNdk.get().getNdkVersion(), "14") >= 0;
  }

  public void assumeSdkIsAvailable() {
    assumeTrue(sdkResolver.isPresent());
  }

  /**
   * Checks that Android SDK has build tools with aapt2 that supports `--output-test-symbols`.
   *
   * <p>It seems that this option appeared in build-tools 26.0.2 and the check only verifies the
   * version of build tools, it doesn't run aapt2 to verify it actually supports the option.
   */
  public void assumeAapt2WithOutputTextSymbolsIsAvailable() {
    assumeBuildToolsVersionIsAtLeast("26.0.2");
    assumeAapt2IsAvailable();
  }

  private void assumeAapt2IsAvailable() {
    assumeTrue(sdkResolver.isPresent() && sdkResolver.get().hasAapt2());
  }

  /**
   * Checks that the Android build tools version is newer than or equal to the given version.
   *
   * @param expectedVersion a build tools version in a valid format, e.g. "25.0.2".
   */
  public void assumeBuildToolsVersionIsAtLeast(String expectedVersion) {
    assumeTrue(
        "Build tools version is less than expected version " + expectedVersion,
        sdkResolver.isPresent() && sdkResolver.get().isBuildToolsVersionAtLeast(expectedVersion));
  }

  public void assumeBundleBuildIsSupported() {
    assumeBuildToolsVersionIsAtLeast("28");
    assumeAapt2IsAvailable();
  }
}
