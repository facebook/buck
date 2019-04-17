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

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import com.android.bundle.Config.BundleConfig;
import com.android.common.SdkConstants;
import com.android.common.sdklib.build.ApkBuilder;
import com.android.sdklib.build.ApkCreationException;
import com.android.sdklib.build.DuplicateFileException;
import com.android.sdklib.build.IArchiveBuilder;
import com.android.sdklib.build.SealedApkException;
import com.android.tools.build.bundletool.commands.BuildBundleCommand;
import com.android.tools.build.bundletool.model.BundleModule;
import com.android.tools.build.bundletool.utils.files.BufferedIo;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;

/**
 * Merges resources into a final Android App Bundle. This code is based off of the now deprecated
 * apkbuilder tool:
 */
public class AabBuilderStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path pathToOutputAabFile;
  private final Optional<Path> pathToBundleConfigFile;
  private BuildTarget buildTarget;
  private final boolean debugMode;
  private static final Pattern PATTERN_NATIVELIB_EXT =
      Pattern.compile("^.+\\.so$", Pattern.CASE_INSENSITIVE);
  private static final Pattern PATTERN_BITCODELIB_EXT =
      Pattern.compile("^.+\\.bc$", Pattern.CASE_INSENSITIVE);
  private final ImmutableSet<ModuleInfo> modulesInfo;
  private final ImmutableSet<String> moduleNames;

  /**
   * @param modulesInfo A set of ModuleInfo containing information about modules to be built within
   *     this bundle
   * @param pathToOutputAabFile Path to output our AAB to.
   * @param debugMode Whether or not to run ApkBuilder with debug mode turned on.
   */
  public AabBuilderStep(
      ProjectFilesystem filesystem,
      Path pathToOutputAabFile,
      Optional<Path> pathToBundleConfigFile,
      BuildTarget buildTarget,
      boolean debugMode,
      ImmutableSet<ModuleInfo> modulesInfo,
      ImmutableSet<String> moduleNames) {
    this.filesystem = filesystem;
    this.pathToOutputAabFile = pathToOutputAabFile;
    this.pathToBundleConfigFile = pathToBundleConfigFile;
    this.buildTarget = buildTarget;
    this.debugMode = debugMode;
    this.modulesInfo = modulesInfo;
    this.moduleNames = moduleNames;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) throws IOException {
    PrintStream output = null;
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      output = context.getStdOut();
    }

    ImmutableList.Builder<Path> modulesPathsBuilder = new Builder<>();
    File fakeResApk = filesystem.createTempFile("fake", ".txt").toFile();
    Set<String> addedFiles = new HashSet<>();
    Set<Path> addedSourceFiles = new HashSet<>();

    for (ModuleInfo moduleInfo : modulesInfo) {
      Path moduleGenPath = getPathForModule(moduleInfo);
      StepExecutionResult moduleBuildResult =
          addModule(
              context, moduleGenPath, fakeResApk, output, moduleInfo, addedFiles, addedSourceFiles);
      if (!moduleBuildResult.isSuccess()) {
        return moduleBuildResult;
      }
      modulesPathsBuilder.add(moduleGenPath);
    }

    BuildBundleCommand.Builder bundleBuilder =
        BuildBundleCommand.builder()
            .setOutputPath(pathToOutputAabFile)
            .setModulesPaths(modulesPathsBuilder.build());

    if (pathToBundleConfigFile.isPresent()) {
      bundleBuilder.setBundleConfig(parseBundleConfigJson(pathToBundleConfigFile.get()));
    }
    bundleBuilder.build().execute();
    return StepExecutionResults.SUCCESS;
  }

  // To be removed when https://github.com/google/bundletool/issues/63 is fixed
  private static BundleConfig parseBundleConfigJson(Path bundleConfigJsonPath) {
    BundleConfig.Builder bundleConfig = BundleConfig.newBuilder();
    try (Reader bundleConfigReader = BufferedIo.reader(bundleConfigJsonPath)) {
      JsonFormat.parser().merge(bundleConfigReader, bundleConfig);
    } catch (InvalidProtocolBufferException e) {
      throw new HumanReadableException(
          e,
          String.format(
              "The file '%s' is not a valid BundleConfig JSON file.", bundleConfigJsonPath));
    } catch (IOException e) {
      throw new HumanReadableException(
          e,
          String.format(
              "An error occurred while trying to read the file '%s'.", bundleConfigJsonPath));
    }
    return bundleConfig.build();
  }

  private void addFile(
      IArchiveBuilder builder,
      Path file,
      String destination,
      Set<String> addedFiles,
      Set<Path> addedSourceFiles)
      throws SealedApkException, DuplicateFileException, ApkCreationException {
    if (addedFiles.contains(destination) || addedSourceFiles.contains(file)) {
      return;
    }
    builder.addFile(file.toFile(), destination);
    addedFiles.add(destination);
    addedSourceFiles.add(file);
  }

  private Path getPathForModule(ModuleInfo moduleInfo) {
    return filesystem
        .getBuckPaths()
        .getGenDir()
        .resolve(buildTarget.getBasePath())
        .resolve(String.format("%s.zip", moduleInfo.getModuleName()));
  }

  private StepExecutionResult addModule(
      ExecutionContext context,
      Path moduleGenPath,
      File fakeResApk,
      @Nullable PrintStream verboseStream,
      ModuleInfo moduleInfo,
      Set<String> addedFiles,
      Set<Path> addedSourceFiles)
      throws IOException {

    try {
      ApkBuilder moduleBuilder =
          new ApkBuilder(moduleGenPath.toFile(), fakeResApk, null, null, null, verboseStream);
      addModuleFiles(moduleBuilder, moduleInfo, addedFiles, addedSourceFiles);
      // Build the APK
      moduleBuilder.sealApk();
    } catch (ApkCreationException | SealedApkException e) {
      context.logError(e, "Error when creating APK at: %s.", moduleGenPath);
      return StepExecutionResults.ERROR;
    } catch (DuplicateFileException e) {
      throw new HumanReadableException(
          String.format(
              "Found duplicate file for APK: %1$s\nOrigin 1: %2$s\nOrigin 2: %3$s",
              e.getArchivePath(), e.getFile1(), e.getFile2()));
    }
    return StepExecutionResults.SUCCESS;
  }

  private void addModuleFiles(
      ApkBuilder moduleBuilder,
      ModuleInfo moduleInfo,
      Set<String> addedFiles,
      Set<Path> addedSourceFiles)
      throws ApkCreationException, DuplicateFileException, SealedApkException, IOException {

    moduleBuilder.setDebugMode(debugMode);
    if (moduleInfo.getDexFile() != null) {
      for (Path dexFile : moduleInfo.getDexFile()) {
        if (moduleInfo.isBaseModule()) {
          addFile(
              moduleBuilder,
              filesystem.getPathForRelativePath(dexFile),
              getDexFileName(addedFiles),
              addedFiles,
              addedSourceFiles);
        } else {
          addFile(
              moduleBuilder,
              filesystem.getPathForRelativePath(dexFile),
              Paths.get(BundleModule.ASSETS_DIRECTORY.toString())
                  .resolve(dexFile.getFileName())
                  .toString(),
              addedFiles,
              addedSourceFiles);
        }
      }
    }
    if (moduleInfo.getResourceApk() != null) {
      if (moduleInfo.isBaseModule()) {
        packageFile(
            moduleBuilder,
            moduleInfo.getResourceApk().toFile(),
            resolve(null, ""),
            addedFiles,
            addedSourceFiles);
      } else {
        processFileForResource(
            moduleBuilder,
            filesystem.getPathForRelativePath(moduleInfo.getResourceApk()).toFile(),
            "",
            addedFiles,
            addedSourceFiles);
      }
    }
    if (moduleInfo.getNativeLibraryDirectories() != null) {
      for (Path nativeLibraryDirectory : moduleInfo.getNativeLibraryDirectories()) {
        addNativeLibraries(
            moduleBuilder,
            filesystem.getPathForRelativePath(nativeLibraryDirectory).toFile(),
            addedFiles,
            addedSourceFiles);
      }
    }
    if (moduleInfo.getAssetDirectories() != null) {
      for (Path assetDirectory : moduleInfo.getAssetDirectories().keySet()) {
        String subFolderName = moduleInfo.getAssetDirectories().get(assetDirectory);
        addSourceFolder(
            moduleBuilder,
            filesystem.getPathForRelativePath(assetDirectory).toFile(),
            subFolderName.isEmpty() ? "" : resolve(null, subFolderName),
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
            moduleBuilder,
            filesystem.getPathForRelativePath(zipFile).toFile(),
            resolve(null, ""),
            addedFiles,
            addedSourceFiles);
      }
    }
    if (moduleInfo.getJarFilesThatMayContainResources() != null) {
      for (Path jarFileThatMayContainResources : moduleInfo.getJarFilesThatMayContainResources()) {
        Path jarFile = filesystem.getPathForRelativePath(jarFileThatMayContainResources);
        packageFile(
            moduleBuilder,
            filesystem.getPathForRelativePath(jarFile).toFile(),
            resolve(null, ""),
            addedFiles,
            addedSourceFiles);
      }
    }
  }

  private void addSourceFolder(
      ApkBuilder builder,
      File sourceFolder,
      String destination,
      Set<String> addedFiles,
      Set<Path> addedSourceFiles)
      throws ApkCreationException, DuplicateFileException, SealedApkException {
    if (sourceFolder.isDirectory()) {
      File[] files = sourceFolder.listFiles();
      if (files == null) {
        return;
      }
      for (File file : files) {
        processFileForResource(builder, file, destination, addedFiles, addedSourceFiles);
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
      Set<String> addedFiles,
      Set<Path> addedSourceFiles)
      throws DuplicateFileException, ApkCreationException, SealedApkException {
    path = resolve(path, file.getName());
    if (file.isDirectory() && ApkBuilder.checkFolderForPackaging(file.getName())) {
      if (file.getName().equals(BundleModule.RESOURCES_DIRECTORY.toString())) {
        path = resolve(null, BundleModule.RESOURCES_DIRECTORY.toString());
      }
      File[] files = file.listFiles();
      if (files == null) {
        return;
      }
      for (File contentFile : files) {
        processFileForResource(builder, contentFile, path, addedFiles, addedSourceFiles);
      }
    } else if (!file.isDirectory() && ApkBuilder.checkFileForPackaging(file.getName())) {
      if (file.getName().endsWith(".dex")) {
        addFile(builder, file.toPath(), getDexFileName(addedFiles), addedFiles, addedSourceFiles);
      } else if (file.getName().equals(BundleModule.MANIFEST_FILENAME)) {
        addFile(
            builder,
            file.toPath(),
            Paths.get(BundleModule.MANIFEST_DIRECTORY.toString())
                .resolve(file.getName())
                .toString(),
            addedFiles,
            addedSourceFiles);
      } else if (file.getName().equals(BundleModule.RESOURCES_PROTO_PATH.toString())) {
        addFile(
            builder,
            file.toPath(),
            Paths.get(file.getName()).toString(),
            addedFiles,
            addedSourceFiles);
      } else {
        addFile(builder, file.toPath(), path, addedFiles, addedSourceFiles);
      }
    }
  }

  private String getDexFileName(Set<String> addedFiles) {
    int ind = 1;
    String possibleName = Paths.get("dex").resolve("classes.dex").toString();
    while (addedFiles.contains(possibleName)) {
      ind++;
      possibleName = Paths.get("dex").resolve("classes" + ind + ".dex").toString();
    }
    return possibleName;
  }

  private String resolve(@Nullable String path, String fileName) {
    return path == null ? fileName : path + File.separator + fileName;
  }

  private void addNativeLibraries(
      ApkBuilder builder, File nativeFolder, Set<String> addedFiles, Set<Path> addedSourceFiles)
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
            Paths.get(BundleModule.LIB_DIRECTORY.toString())
                .resolve(abi.getName())
                .resolve(lib.getName());

        addFile(builder, lib.toPath(), libPath.toString(), addedFiles, addedSourceFiles);
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

        if (isEntryPackageable(entry)) {

          String location = resolveFileInModule(entry);
          addFile(
              builder,
              convertZipEntryToFile(zipFile, entry).toPath(),
              destination + location + entry.getName(),
              addedFiles,
              addedSourceFiles);
        }
      }
    }
  }

  /**
   * Defines if a zip entry should be packaged in the final bundle.
   *
   * @param entry
   * @return true if entry should be packaged
   */
  private boolean isEntryPackageable(ZipEntry entry) {
    return isDirectoryEntryPackageable(entry) || isFileEntryPackageable(entry);
  }

  private boolean isDirectoryEntryPackageable(ZipEntry entry) {
    return entry.isDirectory() && ApkBuilder.checkFolderForPackaging(entry.getName());
  }

  private boolean isFileEntryPackageable(ZipEntry entry) {
    return ApkBuilder.checkFileForPackaging(entry.getName())
        && isValidMetaInfEntry(entry.getName());
  }

  // We should filter out anything from META-INF (except for the ones responsible for the
  // apk validation itself). As described on:
  // https://android.googlesource.com/platform/sdk/+/e162064a7b5db1eecec34271bc7e2a4296181ea6/sdkmanager/libs/sdklib/src/com/android/sdklib/build/ApkBuilder.java#105
  // and https://source.android.com/security/apksigning/v2#v1-verification
  private boolean isValidMetaInfEntry(String entryName) {
    String metaInfDir = "META-INF";
    if (!entryName.startsWith(metaInfDir)) {
      // Not a meta inf file, so it's valid concerning this check
      return true;
    }

    return entryName.equals(metaInfDir + File.separator + "CERT.SF")
        || entryName.equals(metaInfDir + File.separator + "MANIFEST.MF")
        || entryName.equals(metaInfDir + File.separator + "CERT.RSA");
  }

  private String resolveFileInModule(ZipEntry entry) {
    String location;
    String fileSeparator = "/";
    String empty = "";

    if (entry.getName().equals(BundleModule.MANIFEST_FILENAME)) {
      location = resolve(BundleModule.MANIFEST_DIRECTORY.toString(), empty);

    } else if (entry.getName().startsWith(BundleModule.LIB_DIRECTORY.toString() + fileSeparator)
        || entry.getName().startsWith(BundleModule.RESOURCES_DIRECTORY.toString() + fileSeparator)
        || entry.getName().startsWith(BundleModule.ASSETS_DIRECTORY.toString() + fileSeparator)
        || entry.getName().startsWith(BundleModule.DEX_DIRECTORY.toString() + fileSeparator)
        || entry.getName().endsWith(".pb")) {
      // They are already in the right folder
      location = empty;

    } else {
      location = resolve(BundleModule.ROOT_DIRECTORY.toString(), empty);
    }

    return location;
  }

  private File convertZipEntryToFile(ZipFile zipFile, ZipEntry ze) throws IOException {
    Path tempFilePath = filesystem.createTempFile("tempRes", ".txt");
    File tempFile = tempFilePath.toFile();
    tempFile.deleteOnExit();
    try (InputStream in = zipFile.getInputStream(ze)) {
      Files.copy(in, tempFilePath, REPLACE_EXISTING);
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
