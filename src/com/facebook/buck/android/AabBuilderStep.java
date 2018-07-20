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

import com.android.common.SdkConstants;
import com.android.common.sdklib.build.ApkBuilder;
import com.android.sdklib.build.ApkCreationException;
import com.android.sdklib.build.DuplicateFileException;
import com.android.sdklib.build.IArchiveBuilder;
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
import java.nio.file.Paths;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.Enumeration;
import java.util.function.Supplier;
import java.util.regex.Pattern;
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
  private final Path tempNativeLib;
  private final ImmutableSet<Path> zipFiles;
  private final ImmutableSet<Path> jarFilesThatMayContainResources;
  private final boolean debugMode;
  private final int apkCompressionLevel;
  private final Path tempBundleConfig;
  private static final Pattern PATTERN_NATIVELIB_EXT =
      Pattern.compile("^.+\\.so$", Pattern.CASE_INSENSITIVE);
  private static final Pattern PATTERN_BITCODELIB_EXT =
      Pattern.compile("^.+\\.bc$", Pattern.CASE_INSENSITIVE);
  private final String moduleName = "base";

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
      Path tempNative,
      ImmutableSet<Path> zipFiles,
      ImmutableSet<Path> jarFilesThatMayContainResources,
      Path pathToKeystore,
      Supplier<KeystoreProperties> keystorePropertiesSupplier,
      boolean debugMode,
      ImmutableList<String> javaRuntimeLauncher,
      int apkCompressionLevel,
      Path tempBundleConfig) {
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
    this.tempNativeLib = tempNative;
    this.jarFilesThatMayContainResources = jarFilesThatMayContainResources;
    this.zipFiles = zipFiles;
    this.debugMode = debugMode;
    this.apkCompressionLevel = apkCompressionLevel;
    this.tempBundleConfig = tempBundleConfig;
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
      builder.addFile(
          filesystem.getPathForRelativePath(dexFile).toFile(),
          Paths.get(moduleName).resolve("dex").resolve("classes.dex").toString());
      packageFile(builder, resourceApk.toFile(), resolve(moduleName, ""));
      builder.addFile(
          filesystem.getPathForRelativePath(tempAssets).toFile(),
          Paths.get(moduleName).resolve("assets.pb").toString());
      builder.addFile(
          filesystem.getPathForRelativePath(tempNativeLib).toFile(),
          Paths.get(moduleName).resolve("native.pb").toString());
      builder.addFile(
          filesystem.getPathForRelativePath(tempBundleConfig).toFile(), "BundleConfig.pb");

      for (Path nativeLibraryDirectory : nativeLibraryDirectories) {
        addNativeLibraries(
            builder, filesystem.getPathForRelativePath(nativeLibraryDirectory).toFile());
      }
      for (Path assetDirectory : assetDirectories) {
        addSourceFolder(builder, filesystem.getPathForRelativePath(assetDirectory).toFile());
      }
      for (Path zipFile : zipFiles) {
        // TODO(natthu): Skipping silently is bad. These should really be assertions.
        if (filesystem.exists(zipFile) && filesystem.isFile(zipFile)) {
          packageFile(
              builder,
              filesystem.getPathForRelativePath(zipFile).toFile(),
              resolve(moduleName, ""));
        }
      }
      for (Path jarFileThatMayContainResources : jarFilesThatMayContainResources) {
        Path jarFile = filesystem.getPathForRelativePath(jarFileThatMayContainResources);
        packageFile(
            builder, filesystem.getPathForRelativePath(jarFile).toFile(), resolve(moduleName, ""));
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

  private void addSourceFolder(ApkBuilder builder, File sourceFolder)
      throws ApkCreationException, DuplicateFileException, SealedApkException {
    if (sourceFolder.isDirectory()) {
      File[] files = sourceFolder.listFiles();
      if (files == null) {
        return;
      }
      for (File file : files) {
        processFileForResource(builder, file, "base");
      }
    } else {
      if (sourceFolder.exists()) {
        throw new ApkCreationException("%s is not a folder", sourceFolder);
      } else {
        throw new ApkCreationException("%s does not exist", sourceFolder);
      }
    }
  }

  private void processFileForResource(IArchiveBuilder builder, File file, String path)
      throws DuplicateFileException, ApkCreationException, SealedApkException {
    path = resolve(path, file.getName());
    if (file.isDirectory() && ApkBuilder.checkFolderForPackaging(file.getName())) {
      File[] files = file.listFiles();
      if (files == null) {
        return;
      }
      for (File contentFile : files) {
        processFileForResource(builder, contentFile, path);
      }
    } else if (!file.isDirectory() && ApkBuilder.checkFileForPackaging(file.getName())) {
      builder.addFile(file, path);
    }
  }

  private String resolve(String path, String fileName) {
    return path == null ? fileName : path + File.separator + fileName;
  }

  private void addNativeLibraries(ApkBuilder builder, File nativeFolder)
      throws ApkCreationException, SealedApkException, DuplicateFileException {
    if (!nativeFolder.isDirectory()) {
      if (nativeFolder.exists()) {
        throw new ApkCreationException("%s is not a folder", nativeFolder);
      } else {
        throw new ApkCreationException("%s does not exist", nativeFolder);
      }
    }
    File[] abiList = nativeFolder.listFiles();
    if (abiList == null) {
      return;
    }
    for (File abi : abiList) {
      if (!abi.isDirectory()) {
        continue;
      }
      File[] libs = abi.listFiles();
      if (libs == null) {
        continue;
      }
      for (File lib : libs) {
        if (!addFileToBuilder(lib)) {
          continue;
        }
        Path libPath =
            Paths.get(moduleName)
                .resolve(SdkConstants.FD_APK_NATIVE_LIBS)
                .resolve(abi.getName())
                .resolve(lib.getName());

        builder.addFile(lib, libPath.toString());
      }
    }
  }

  private boolean addFileToBuilder(File lib) {
    return lib.isFile()
        && (PATTERN_NATIVELIB_EXT.matcher(lib.getName()).matches()
            || PATTERN_BITCODELIB_EXT.matcher(lib.getName()).matches()
            || (debugMode && SdkConstants.FN_GDBSERVER.equals(lib.getName())));
  }

  private void packageFile(ApkBuilder builder, File original, String destination)
      throws IOException, ApkCreationException, DuplicateFileException, SealedApkException {
    try (ZipFile zipFile = new ZipFile(original)) {
      Enumeration<? extends ZipEntry> zipEntryEnumeration = zipFile.entries();
      while (zipEntryEnumeration.hasMoreElements()) {
        ZipEntry entry = zipEntryEnumeration.nextElement();
        String location =
            entry.getName().equals("AndroidManifest.xml") ? "manifest" + File.separator : "";
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
