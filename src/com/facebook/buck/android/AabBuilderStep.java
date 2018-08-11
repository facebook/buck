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
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Joiner;
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
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Merges resources into a final Android App Bundle. This code is based off of the now deprecated
 * apkbuilder tool:
 */
public class AabBuilderStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path pathToOutputApkFile;
  private final boolean debugMode;
  private final int apkCompressionLevel;
  private final Path tempBundleConfig;
  private static final Pattern PATTERN_NATIVELIB_EXT =
      Pattern.compile("^.+\\.so$", Pattern.CASE_INSENSITIVE);
  private static final Pattern PATTERN_BITCODELIB_EXT =
      Pattern.compile("^.+\\.bc$", Pattern.CASE_INSENSITIVE);
  private final ImmutableSet<ModuleInfo> modulesInfo;
  private final AppBuilderBase appBuilderBase;
  private final ImmutableSet<String> moduleNames;

  /**
   * @param modulesInfo A set of ModuleInfo containing information about modules to be built within
   *     this bundle
   * @param pathToOutputApkFile Path to output our APK to.
   * @param debugMode Whether or not to run ApkBuilder with debug mode turned on.
   * @param apkCompressionLevel
   */
  public AabBuilderStep(
      ProjectFilesystem filesystem,
      Path pathToOutputApkFile,
      Path pathToKeystore,
      Supplier<KeystoreProperties> keystorePropertiesSupplier,
      boolean debugMode,
      int apkCompressionLevel,
      Path tempBundleConfig,
      ImmutableSet<ModuleInfo> modulesInfo,
      ImmutableSet<String> moduleNames) {
    this.filesystem = filesystem;
    this.pathToOutputApkFile = pathToOutputApkFile;
    this.debugMode = debugMode;
    this.apkCompressionLevel = apkCompressionLevel;
    this.tempBundleConfig = tempBundleConfig;
    this.modulesInfo = modulesInfo;
    this.moduleNames = moduleNames;
    this.appBuilderBase =
        new AppBuilderBase(filesystem, keystorePropertiesSupplier, pathToKeystore);
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    PrintStream output = null;
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      output = context.getStdOut();
    }

    try {
      AppBuilderBase.PrivateKeyAndCertificate privateKeyAndCertificate =
          appBuilderBase.createKeystoreProperties();
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
      Set<String> addedFiles = new HashSet<>();
      Set<Path> addedSourceFiles = new HashSet<>();
      for (ModuleInfo moduleInfo : modulesInfo) {
        addModule(builder, moduleInfo, addedFiles, addedSourceFiles);
      }
      builder.addFile(
          filesystem.getPathForRelativePath(tempBundleConfig).toFile(), "BundleConfig.pb");
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

  private void addFile(
      IArchiveBuilder builder,
      Path file,
      String destination,
      boolean isZip,
      Set<String> addedFiles,
      Set<Path> addedSourceFiles)
      throws SealedApkException, DuplicateFileException, ApkCreationException {
    if (!isZip && (addedFiles.contains(destination) || addedSourceFiles.contains(file))) {
      return;
    }
    builder.addFile(file.toFile(), destination);
    addedFiles.add(destination);
    addedSourceFiles.add(file);
  }

  private void addModule(
      ApkBuilder builder, ModuleInfo moduleInfo, Set<String> addedFiles, Set<Path> addedSourceFiles)
      throws ApkCreationException, DuplicateFileException, SealedApkException, IOException {
    String moduleName = moduleInfo.getModuleName();
    if (moduleInfo.getDexFile() != null) {
      for (Path dexFile : moduleInfo.getDexFile()) {
        if (moduleName.equals("base")) {
          addFile(
              builder,
              filesystem.getPathForRelativePath(dexFile),
              getDexFileName(moduleName, addedFiles),
              false,
              addedFiles,
              addedSourceFiles);
        } else {
          addFile(
              builder,
              filesystem.getPathForRelativePath(dexFile),
              Paths.get(moduleName).resolve("assets").resolve(dexFile.getFileName()).toString(),
              false,
              addedFiles,
              addedSourceFiles);
        }
      }
    }
    if (moduleInfo.getResourceApk() != null) {
      if (moduleName.equals("base")) {
        packageFile(
            builder,
            moduleInfo.getResourceApk().toFile(),
            resolve(moduleName, ""),
            addedFiles,
            addedSourceFiles);
      } else {
        processFileForResource(
            builder,
            filesystem.getPathForRelativePath(moduleInfo.getResourceApk()).toFile(),
            "",
            moduleName,
            addedFiles,
            addedSourceFiles);
      }
    }
    if (moduleInfo.getTempAssets() != null) {
      addFile(
          builder,
          filesystem.getPathForRelativePath(moduleInfo.getTempAssets()),
          Paths.get(moduleName).resolve("assets.pb").toString(),
          false,
          addedFiles,
          addedSourceFiles);
    }
    if (moduleInfo.getTempNatives() != null) {
      addFile(
          builder,
          filesystem.getPathForRelativePath(moduleInfo.getTempNatives()),
          Paths.get(moduleName).resolve("native.pb").toString(),
          false,
          addedFiles,
          addedSourceFiles);
    }
    if (moduleInfo.getNativeLibraryDirectories() != null) {
      for (Path nativeLibraryDirectory : moduleInfo.getNativeLibraryDirectories()) {
        addNativeLibraries(
            builder,
            filesystem.getPathForRelativePath(nativeLibraryDirectory).toFile(),
            moduleName,
            addedFiles,
            addedSourceFiles);
      }
    }
    if (moduleInfo.getAssetDirectories() != null) {
      for (Path assetDirectory : moduleInfo.getAssetDirectories().keySet()) {
        String subFolderName = moduleInfo.getAssetDirectories().get(assetDirectory);
        addSourceFolder(
            builder,
            filesystem.getPathForRelativePath(assetDirectory).toFile(),
            subFolderName.isEmpty() ? moduleName : resolve(moduleName, subFolderName),
            moduleName,
            addedFiles,
            addedSourceFiles);
      }
    }
    if (moduleInfo.getZipFiles() != null) {
      for (Path zipFile : moduleInfo.getZipFiles()) {
        if (!filesystem.exists(zipFile) || !filesystem.isFile(zipFile)) {
          continue;
        }
        packageFile(
            builder,
            filesystem.getPathForRelativePath(zipFile).toFile(),
            resolve(moduleName, ""),
            addedFiles,
            addedSourceFiles);
      }
    }
    if (moduleInfo.getJarFilesThatMayContainResources() != null) {
      for (Path jarFileThatMayContainResources : moduleInfo.getJarFilesThatMayContainResources()) {
        Path jarFile = filesystem.getPathForRelativePath(jarFileThatMayContainResources);
        packageFile(
            builder,
            filesystem.getPathForRelativePath(jarFile).toFile(),
            resolve(moduleName, ""),
            addedFiles,
            addedSourceFiles);
      }
    }
  }

  private void addSourceFolder(
      ApkBuilder builder,
      File sourceFolder,
      String destination,
      String moduleName,
      Set<String> addedFiles,
      Set<Path> addedSourceFiles)
      throws ApkCreationException, DuplicateFileException, SealedApkException {
    if (sourceFolder.isDirectory()) {
      File[] files = sourceFolder.listFiles();
      if (files == null) {
        return;
      }
      for (File file : files) {
        processFileForResource(
            builder, file, destination, moduleName, addedFiles, addedSourceFiles);
      }
    } else {
      if (sourceFolder.exists()) {
        throw new ApkCreationException("%s is not a folder", sourceFolder);
      } else {
        throw new ApkCreationException("%s does not exist", sourceFolder);
      }
    }
  }

  private void processFileForResource(
      IArchiveBuilder builder,
      File file,
      String path,
      String moduleName,
      Set<String> addedFiles,
      Set<Path> addedSourceFiles)
      throws DuplicateFileException, ApkCreationException, SealedApkException {
    path = resolve(path, file.getName());
    if (file.isDirectory() && ApkBuilder.checkFolderForPackaging(file.getName())) {
      if (file.getName().equals("res")) {
        path = resolve(moduleName, "res");
      }
      File[] files = file.listFiles();
      if (files == null) {
        return;
      }
      for (File contentFile : files) {
        processFileForResource(
            builder, contentFile, path, moduleName, addedFiles, addedSourceFiles);
      }
    } else if (!file.isDirectory() && ApkBuilder.checkFileForPackaging(file.getName())) {
      if (file.getName().endsWith(".dex")) {
        addFile(
            builder,
            file.toPath(),
            getDexFileName(moduleName, addedFiles),
            false,
            addedFiles,
            addedSourceFiles);
      } else if (file.getName().equals("AndroidManifest.xml")) {
        addFile(
            builder,
            file.toPath(),
            Paths.get(moduleName).resolve("manifest").resolve(file.getName()).toString(),
            false,
            addedFiles,
            addedSourceFiles);
      } else if (file.getName().equals("resources.pb")) {
        addFile(
            builder,
            file.toPath(),
            Paths.get(moduleName).resolve(file.getName()).toString(),
            false,
            addedFiles,
            addedSourceFiles);
      } else {
        addFile(builder, file.toPath(), path, false, addedFiles, addedSourceFiles);
      }
    }
  }

  private String getDexFileName(String moduleName, Set<String> addedFiles) {
    int ind = 1;
    String possibleName = Paths.get(moduleName).resolve("dex").resolve("classes.dex").toString();
    while (addedFiles.contains(possibleName)) {
      ind++;
      possibleName =
          Paths.get(moduleName).resolve("dex").resolve("classes" + ind + ".dex").toString();
    }
    return possibleName;
  }

  private String resolve(String path, String fileName) {
    return path == null ? fileName : path + File.separator + fileName;
  }

  private void addNativeLibraries(
      ApkBuilder builder,
      File nativeFolder,
      String moduleName,
      Set<String> addedFiles,
      Set<Path> addedSourceFiles)
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

        addFile(builder, lib.toPath(), libPath.toString(), false, addedFiles, addedSourceFiles);
      }
    }
  }

  private boolean addFileToBuilder(File lib) {
    return lib.isFile()
        && (PATTERN_NATIVELIB_EXT.matcher(lib.getName()).matches()
            || PATTERN_BITCODELIB_EXT.matcher(lib.getName()).matches()
            || (debugMode && SdkConstants.FN_GDBSERVER.equals(lib.getName())));
  }

  private void packageFile(
      ApkBuilder builder,
      File original,
      String destination,
      Set<String> addedFiles,
      Set<Path> addedSourceFiles)
      throws IOException, ApkCreationException, DuplicateFileException, SealedApkException {
    if (addedSourceFiles.contains(original.toPath())) {
      return;
    }
    try (ZipFile zipFile = new ZipFile(original)) {
      Enumeration<? extends ZipEntry> zipEntryEnumeration = zipFile.entries();
      while (zipEntryEnumeration.hasMoreElements()) {
        ZipEntry entry = zipEntryEnumeration.nextElement();
        String location =
            entry.getName().equals("AndroidManifest.xml") ? resolve("manifest", "") : "";
        addFile(
            builder,
            convertZipEntryToFile(zipFile, entry).toPath(),
            destination + location + entry.getName(),
            true,
            addedFiles,
            addedSourceFiles);
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
  public String getDescription(ExecutionContext context) {
    String summaryOfModules = Joiner.on(',').join(moduleNames);
    return String.format("Build a Bundle contains following modules: %s", summaryOfModules);
  }

  @Override
  public String getShortName() {
    return "aab_builder";
  }
}
