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

import com.android.common.sdklib.build.ApkBuilder;
import com.android.sdklib.build.ApkCreationException;
import com.android.sdklib.build.DuplicateFileException;
import com.android.sdklib.build.SealedApkException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.Enumeration;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Merges resources into a final Android App Bundle. This code is based off of the now deprecated
 * apkbuilder tool:
 * https://android.googlesource.com/platform/sdk/+/fd30096196e3747986bdf8a95cc7713dd6e0b239%5E/sdkmanager/libs/sdklib/src/main/java/com/android/sdklib/build/ApkBuilderMain.java
 */
public class AabBuilderStep extends ApkBuilderStep {

  private final ProjectFilesystem filesystem;
  private final Path resourceApk;
  private final Path dexFile;
  private final Path pathToOutputApkFile;
  private final ImmutableSet<Path> assetDirectories;
  private final Path tempAssets;
  private final ImmutableSet<Path> nativeLibraryDirectories;
  private final ImmutableSet<Path> zipFiles;
  private final ImmutableSet<Path> jarFilesThatMayContainResources;
  private final boolean debugMode;
  private final int apkCompressionLevel;

  /**
   * @param resourceApk Path to the Apk which only contains resources, no dex files.
   * @param pathToOutputApkFile Path to output our APK to.
   * @param dexFile Path to the classes.dex file.
   * @param assetDirectories List of paths to assets to be included in the apk.
   * @param nativeLibraryDirectories List of paths to native directories.
   * @param zipFiles List of paths to zipfiles to be included into the apk.
   * @param debugMode Whether or not to run ApkBuilder with debug mode turned on.
   * @param apkCompressionLevel
   */
  public AabBuilderStep(
      ProjectFilesystem filesystem,
      Path resourceApk,
      Path pathToOutputApkFile,
      Path dexFile,
      ImmutableSet<Path> assetDirectories,
      Path tempAssets,
      ImmutableSet<Path> nativeLibraryDirectories,
      ImmutableSet<Path> zipFiles,
      ImmutableSet<Path> jarFilesThatMayContainResources,
      Path pathToKeystore,
      Supplier<KeystoreProperties> keystorePropertiesSupplier,
      boolean debugMode,
      ImmutableList<String> javaRuntimeLauncher,
      int apkCompressionLevel) {
    super(
        filesystem,
        resourceApk,
        pathToOutputApkFile,
        dexFile,
        assetDirectories,
        nativeLibraryDirectories,
        jarFilesThatMayContainResources,
        zipFiles,
        pathToKeystore,
        keystorePropertiesSupplier,
        debugMode,
        javaRuntimeLauncher,
        apkCompressionLevel);
    this.filesystem = filesystem;
    this.resourceApk = resourceApk;
    this.pathToOutputApkFile = pathToOutputApkFile;
    this.dexFile = dexFile;
    this.assetDirectories = assetDirectories;
    this.tempAssets = tempAssets;
    this.nativeLibraryDirectories = nativeLibraryDirectories;
    this.jarFilesThatMayContainResources = jarFilesThatMayContainResources;
    this.zipFiles = zipFiles;
    this.debugMode = debugMode;
    this.apkCompressionLevel = apkCompressionLevel;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    PrintStream output = null;
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      output = context.getStdOut();
    }

    try {
      PrivateKeyAndCertificate privateKeyAndCertificate = createKeystoreProperties();
      File fakeResApk = filesystem.createTempFile("fake", ".txt").toFile();
      fakeResApk.createNewFile();
      @SuppressWarnings("null")
      ApkBuilder builder =
          new ApkBuilder(
              filesystem.getPathForRelativePath(pathToOutputApkFile).toFile(),
              fakeResApk,
              null,
              privateKeyAndCertificate.privateKey,
              privateKeyAndCertificate.certificate,
              output,
              apkCompressionLevel);
      builder.setDebugMode(debugMode);
      builder.addFile(filesystem.getPathForRelativePath(dexFile).toFile(), "base/dex/classes.dex");
      packageFile(builder, resourceApk.toFile(), "base/");
      builder.addFile(filesystem.getPathForRelativePath(tempAssets).toFile(), "base/assets.pb");

      for (Path nativeLibraryDirectory : nativeLibraryDirectories) {
        builder.addNativeLibraries(
            filesystem.getPathForRelativePath(nativeLibraryDirectory).toFile());
      }
      for (Path assetDirectory : assetDirectories) {
        builder.addSourceFolder(filesystem.getPathForRelativePath(assetDirectory).toFile());
      }
      for (Path zipFile : zipFiles) {
        // TODO(natthu): Skipping silently is bad. These should really be assertions.
        if (filesystem.exists(zipFile) && filesystem.isFile(zipFile)) {
          builder.addZipFile(filesystem.getPathForRelativePath(zipFile).toFile());
        }
      }
      for (Path jarFileThatMayContainResources : jarFilesThatMayContainResources) {
        Path jarFile = filesystem.getPathForRelativePath(jarFileThatMayContainResources);
        builder.addResourcesFromJar(jarFile.toFile());
      }

      // Build the APK
      builder.sealApk();
    } catch (ApkCreationException
        | KeyStoreException
        | NoSuchAlgorithmException
        | SealedApkException
        | UnrecoverableKeyException e) {
      context.logError(e, "Error when creating APK at: %s.", pathToOutputApkFile);
      return StepExecutionResults.ERROR;
    } catch (DuplicateFileException e) {
      throw new HumanReadableException(
          String.format(
              "Found duplicate file for APK: %1$s\nOrigin 1: %2$s\nOrigin 2: %3$s",
              e.getArchivePath(), e.getFile1(), e.getFile2()));
    }
    return StepExecutionResults.SUCCESS;
  }

  private void packageFile(ApkBuilder builder, File original, String destination)
      throws IOException, ApkCreationException, DuplicateFileException, SealedApkException {
    try (ZipFile zipFile = new ZipFile(original)) {
      Enumeration<? extends ZipEntry> zipEntryEnumeration = zipFile.entries();
      while (zipEntryEnumeration.hasMoreElements()) {
        ZipEntry entry = zipEntryEnumeration.nextElement();
        String location = entry.getName().equals("AndroidManifest.xml") ? "manifest/" : "";
        builder.addFile(
            convertZipEntryToFile(zipFile, entry), destination + location + entry.getName());
      }
    }
  }

  private File convertZipEntryToFile(ZipFile zipFile, ZipEntry ze) throws IOException {
    Path tempFilePath = filesystem.createTempFile("tempRes", ".txt");
    File tempFile = tempFilePath.toFile();
    tempFile.deleteOnExit();
    try (InputStream in = zipFile.getInputStream(ze)) {
      Files.copy(in, tempFilePath);
    }
    return tempFile;
  }

  @Override
  public String getShortName() {
    return "aab_builder";
  }
}
