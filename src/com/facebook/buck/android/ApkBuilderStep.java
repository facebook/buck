/*
 * Copyright 2012-present Facebook, Inc.
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

import com.android.sdklib.build.ApkBuilder;
import com.android.sdklib.build.ApkCreationException;
import com.android.sdklib.build.DuplicateFileException;
import com.android.sdklib.build.SealedApkException;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.File;
import java.io.PrintStream;

/**
 * Merges resources into a final APK.  This code is based off of the now deprecated apkbuilder tool:
 * https://android.googlesource.com/platform/sdk/+/fd30096196e3747986bdf8a95cc7713dd6e0b239%5E/sdkmanager/libs/sdklib/src/main/java/com/android/sdklib/build/ApkBuilderMain.java
 */
public class ApkBuilderStep implements Step {

  private final String resourceApk;
  private final String dexFile;
  private final String pathToOutputApkFile;
  private final ImmutableSet<String> assetDirectories;
  private final ImmutableSet<String> nativeLibraryDirectories;
  private final ImmutableSet<String> zipFiles;
  private final boolean debugMode;

  /**
   *
   * @param resourceApk Path to the Apk which only contains resources, no dex files.
   * @param pathToOutputApkFile Path to output our APK to.
   * @param dexFile Path to the classes.dex file.
   * @param javaResourcesDirectories List of paths to resources to be included in the apk.
   * @param nativeLibraryDirectories List of paths to native directories.
   * @param zipFiles List of paths to zipfiles to be included into the apk.
   * @param debugMode Whether or not to run ApkBuilder with debug mode turned on.
   */
  public ApkBuilderStep(
      String resourceApk,
      String pathToOutputApkFile,
      String dexFile,
      ImmutableSet<String> javaResourcesDirectories,
      ImmutableSet<String> nativeLibraryDirectories,
      ImmutableSet<String> zipFiles,
      boolean debugMode) {
    this.resourceApk = Preconditions.checkNotNull(resourceApk);
    this.pathToOutputApkFile = Preconditions.checkNotNull(pathToOutputApkFile);
    this.dexFile = Preconditions.checkNotNull(dexFile);
    this.assetDirectories = Preconditions.checkNotNull(javaResourcesDirectories);
    this.nativeLibraryDirectories = Preconditions.checkNotNull(nativeLibraryDirectories);
    this.zipFiles = Preconditions.checkNotNull(zipFiles);
    this.debugMode = debugMode;
  }

  @Override
  public int execute(ExecutionContext context) {
    PrintStream output = null;
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      output = context.getStdOut();
    }

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    try {
      ApkBuilder builder = new ApkBuilder(
          projectFilesystem.getFileForRelativePath(pathToOutputApkFile),
          projectFilesystem.getFileForRelativePath(resourceApk),
          projectFilesystem.getFileForRelativePath(dexFile),
          /* storeOsPath */ null,
          output);
      builder.setDebugMode(debugMode);
      for (String nativeLibraryDirectory : nativeLibraryDirectories) {
        builder.addNativeLibraries(projectFilesystem.getFileForRelativePath(nativeLibraryDirectory));
      }
      for (String assetDirectory : assetDirectories) {
        builder.addSourceFolder(projectFilesystem.getFileForRelativePath(assetDirectory));
      }
      for (String zipFile : zipFiles) {
        File zipFileOnDisk = projectFilesystem.getFileForRelativePath(zipFile);
        if (zipFileOnDisk.exists() && zipFileOnDisk.isFile()) {
          builder.addZipFile(zipFileOnDisk);
        }
      }

      // Build the APK
      builder.sealApk();
    } catch (ApkCreationException e) {
      throw new HumanReadableException(e.getMessage());
    } catch (DuplicateFileException e) {
      throw new HumanReadableException(
          String.format("Found duplicate file for APK: %1$s\nOrigin 1: %2$s\nOrigin 2: %3$s",
              e.getArchivePath(), e.getFile1(), e.getFile2()));
    } catch (SealedApkException e) {
      throw new HumanReadableException(e.getMessage());
    }
    return 0;
  }

  @Override
  public String getShortName() {
    return "apk_builder";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        // TODO(mbolin): Make the directory that corresponds to $ANDROID_HOME a field that is
        // accessible via an AndroidPlatformTarget and insert that here in place of "$ANDROID_HOME".
        "java -classpath $ANDROID_HOME/tools/lib/sdklib.jar %s %s -v -u %s -z %s %s -f %s",
        "com.android.sdklib.build.ApkBuilderMain",
        pathToOutputApkFile,
        Joiner.on(' ').join(Iterables.transform(nativeLibraryDirectories,
            new Function<String, String>() {
              @Override
              public String apply(String s) {
                return "-nf " + s;
              }
            })),
        resourceApk,
        Joiner.on(' ').join(Iterables.transform(assetDirectories,
            new Function<String, String>() {
              @Override
              public String apply(String s) {
                return "-rf " + s;
              }
            })),
        dexFile);
  }
}
