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

import static com.facebook.buck.android.BinaryType.APK;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Utility class to resolve path conflicts in aab/apk. */
public final class AndroidBinaryPathUtility {

  private AndroidBinaryPathUtility() {}

  /** The APK at this path will have compressed resources, but will not be zipaligned. */
  static Path getCompressedResourcesApkPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget, BinaryType binaryType) {
    return Paths.get(
        getUnsignedApkPath(projectFilesystem, buildTarget, binaryType)
            .replaceAll("\\.unsigned\\.apk$", ".compressed.apk")
            .replaceAll("\\.unsigned\\.aab$", ".compressed.aab"));
  }

  /** The APK at this path will be zipaligned and jar signed. */
  static Path getZipalignedApkPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget, BinaryType binaryType) {
    return Paths.get(
        getUnsignedApkPath(projectFilesystem, buildTarget, binaryType)
            .replaceAll("\\.unsigned\\.apk$", ".zipaligned.apk")
            .replaceAll("\\.unsigned\\.aab$", ".zipaligned.aab"));
  }

  /** The APK at this path will be jar signed, but not zipaligned. */
  static Path getSignedApkPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget, BinaryType binaryType) {
    return Paths.get(
        getUnsignedApkPath(projectFilesystem, buildTarget, binaryType)
            .replaceAll("\\.unsigned\\.apk$", ".signed.apk")
            .replaceAll("\\.unsigned\\.aab$", ".signed.aab"));
  }

  /** The APK at this path will be zipaligned and v2 signed. */
  static Path getFinalApkPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget, BinaryType binaryType) {
    return Paths.get(
        getUnsignedApkPath(projectFilesystem, buildTarget, binaryType)
            .replaceAll("\\.unsigned\\.apk$", ".apk")
            .replaceAll("\\.unsigned\\.aab$", ".aab"));
  }

  /**
   * Directory of text files used by proguard. Unforunately, this contains both inputs and outputs.
   */
  static Path getProguardTextFilesPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    return BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/proguard");
  }

  /** The APK at this path will be optimized with redex */
  static Path getRedexedApkPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget, BinaryType binaryType) {
    Path path = BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s__redex");
    return path.resolve(buildTarget.getShortName() + ".redex." + getExtension(binaryType));
  }

  /** The APK at this path will be unsigned */
  private static String getUnsignedApkPath(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget, BinaryType binaryType) {
    return getPath("%s.unsigned." + getExtension(binaryType), projectFilesystem, buildTarget)
        .toString();
  }

  /** The path to AndroidManifest file */
  static Path getManifestPath(ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    return BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, "%s/AndroidManifest.xml");
  }

  /** All native-libs-as-assets are copied to this directory before running apkbuilder. */
  static Path getPathForNativeLibsAsAssets(
      ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    return BuildTargetPaths.getScratchPath(
        projectFilesystem, buildTarget, "__native_libs_as_assets_%s__");
  }

  private static String getExtension(BinaryType binaryType) {
    return binaryType == APK ? "apk" : "aab";
  }

  private static Path getPath(
      String format, ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    return BuildTargetPaths.getGenPath(projectFilesystem, buildTarget, format);
  }
}
