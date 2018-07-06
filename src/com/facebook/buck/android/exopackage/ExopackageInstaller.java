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

package com.facebook.buck.android.exopackage;

import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.android.ApkInfo;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.NamedTemporaryFile;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.io.Closer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** ExopackageInstaller manages the installation of apps with the "exopackage" flag set to true. */
public class ExopackageInstaller {
  private static final Logger LOG = Logger.get(ExopackageInstaller.class);

  public static final Path EXOPACKAGE_INSTALL_ROOT = Paths.get("/data/local/tmp/exopackage/");
  public static final String SECONDARY_DEX_TYPE = "secondary_dex";
  public static final String NATIVE_LIBRARY_TYPE = "native_library";
  public static final String RESOURCES_TYPE = "resources";

  private final ProjectFilesystem projectFilesystem;
  private final BuckEventBus eventBus;
  private final SourcePathResolver pathResolver;
  private final AndroidDevice device;
  private final String packageName;
  private final Path dataRoot;

  public ExopackageInstaller(
      SourcePathResolver pathResolver,
      ExecutionContext context,
      ProjectFilesystem projectFilesystem,
      String packageName,
      AndroidDevice device) {
    this.pathResolver = pathResolver;
    this.projectFilesystem = projectFilesystem;
    this.eventBus = context.getBuckEventBus();
    this.device = device;
    this.packageName = packageName;
    this.dataRoot = EXOPACKAGE_INSTALL_ROOT.resolve(packageName);

    Preconditions.checkArgument(AdbHelper.PACKAGE_NAME_PATTERN.matcher(packageName).matches());
  }

  /** @return Returns true. */
  // TODO(cjhopman): This return value is silly. Change it to be void.
  public boolean doInstall(ApkInfo apkInfo, @Nullable String processName) throws Exception {
    if (exopackageEnabled(apkInfo)) {
      device.mkDirP(dataRoot.toString());
      ImmutableSortedSet<Path> presentFiles = device.listDirRecursive(dataRoot);
      ExopackageInfo exoInfo = apkInfo.getExopackageInfo().get();
      installMissingExopackageFiles(presentFiles, exoInfo);
      finishExoFileInstallation(presentFiles, exoInfo);
    }
    installApkIfNecessary(apkInfo);
    killApp(apkInfo, processName);
    return true;
  }

  public void killApp(ApkInfo apkInfo, @Nullable String processName) throws Exception {
    // TODO(dreiss): Make this work on Gingerbread.
    try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(eventBus, "kill_app")) {
      // If a specific process name is given and we're not installing a full APK,
      // just kill that process, otherwise kill everything in the package
      if (shouldAppBeInstalled(apkInfo) || processName == null) {
        device.stopPackage(packageName);
      } else {
        device.killProcess(processName);
      }
    }
  }

  public void installApkIfNecessary(ApkInfo apkInfo) throws Exception {
    File apk = pathResolver.getAbsolutePath(apkInfo.getApkPath()).toFile();
    // TODO(dreiss): Support SD installation.
    boolean installViaSd = false;

    if (shouldAppBeInstalled(apkInfo)) {
      try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(eventBus, "install_exo_apk")) {
        boolean success = device.installApkOnDevice(apk, installViaSd, false);
        if (!success) {
          throw new RuntimeException("Installing Apk failed.");
        }
      }
    }
  }

  public void finishExoFileInstallation(
      ImmutableSortedSet<Path> presentFiles, ExopackageInfo exoInfo) throws Exception {
    ImmutableSet.Builder<Path> wantedPaths = ImmutableSet.builder();
    ImmutableMap.Builder<Path, String> metadata = ImmutableMap.builder();

    if (exoInfo.getDexInfo().isPresent()) {
      DexExoHelper dexExoHelper =
          new DexExoHelper(pathResolver, projectFilesystem, exoInfo.getDexInfo().get());
      wantedPaths.addAll(dexExoHelper.getFilesToInstall().keySet());
      metadata.putAll(dexExoHelper.getMetadataToInstall());
    }

    if (exoInfo.getNativeLibsInfo().isPresent()) {
      NativeExoHelper nativeExoHelper =
          new NativeExoHelper(
              device, pathResolver, projectFilesystem, exoInfo.getNativeLibsInfo().get());
      wantedPaths.addAll(nativeExoHelper.getFilesToInstall().keySet());
      metadata.putAll(nativeExoHelper.getMetadataToInstall());
    }

    if (exoInfo.getResourcesInfo().isPresent()) {
      ResourcesExoHelper resourcesExoHelper =
          new ResourcesExoHelper(pathResolver, projectFilesystem, exoInfo.getResourcesInfo().get());
      wantedPaths.addAll(resourcesExoHelper.getFilesToInstall().keySet());
      metadata.putAll(resourcesExoHelper.getMetadataToInstall());
    }

    if (exoInfo.getModuleInfo().isPresent()) {
      ModuleExoHelper moduleExoHelper =
          new ModuleExoHelper(pathResolver, projectFilesystem, exoInfo.getModuleInfo().get());
      wantedPaths.addAll(moduleExoHelper.getFilesToInstall().keySet());
      metadata.putAll(moduleExoHelper.getMetadataToInstall());
    }

    deleteUnwantedFiles(presentFiles, wantedPaths.build());
    installMetadata(metadata.build());
  }

  public void installMissingExopackageFiles(
      ImmutableSortedSet<Path> presentFiles, ExopackageInfo exoInfo) throws Exception {
    if (exoInfo.getDexInfo().isPresent()) {
      DexExoHelper dexExoHelper =
          new DexExoHelper(pathResolver, projectFilesystem, exoInfo.getDexInfo().get());
      installMissingFiles(presentFiles, dexExoHelper.getFilesToInstall(), SECONDARY_DEX_TYPE);
    }

    if (exoInfo.getNativeLibsInfo().isPresent()) {
      NativeExoHelper nativeExoHelper =
          new NativeExoHelper(
              device, pathResolver, projectFilesystem, exoInfo.getNativeLibsInfo().get());
      installMissingFiles(presentFiles, nativeExoHelper.getFilesToInstall(), NATIVE_LIBRARY_TYPE);
    }

    if (exoInfo.getResourcesInfo().isPresent()) {
      ResourcesExoHelper resourcesExoHelper =
          new ResourcesExoHelper(pathResolver, projectFilesystem, exoInfo.getResourcesInfo().get());
      installMissingFiles(presentFiles, resourcesExoHelper.getFilesToInstall(), RESOURCES_TYPE);
    }

    if (exoInfo.getModuleInfo().isPresent()) {
      ModuleExoHelper moduleExoHelper =
          new ModuleExoHelper(pathResolver, projectFilesystem, exoInfo.getModuleInfo().get());
      installMissingFiles(presentFiles, moduleExoHelper.getFilesToInstall(), "modular_dex");
    }
  }

  private boolean exopackageEnabled(ApkInfo apkInfo) {
    return apkInfo
        .getExopackageInfo()
        .map(
            exoInfo ->
                exoInfo.getDexInfo().isPresent()
                    || exoInfo.getNativeLibsInfo().isPresent()
                    || exoInfo.getResourcesInfo().isPresent()
                    || exoInfo.getModuleInfo().isPresent())
        .orElse(false);
  }

  private Optional<PackageInfo> getPackageInfo(String packageName) throws Exception {
    try (SimplePerfEvent.Scope ignored =
        SimplePerfEvent.scope(
            eventBus, PerfEventId.of("get_package_info"), "package", packageName)) {
      return device.getPackageInfo(packageName);
    }
  }

  private boolean shouldAppBeInstalled(ApkInfo apkInfo) throws Exception {
    Optional<PackageInfo> appPackageInfo = getPackageInfo(packageName);
    if (!appPackageInfo.isPresent()) {
      eventBus.post(ConsoleEvent.info("App not installed.  Installing now."));
      return true;
    }

    LOG.debug("App path: %s", appPackageInfo.get().apkPath);
    String installedAppSignature = getInstalledAppSignature(appPackageInfo.get().apkPath);
    String localAppSignature =
        AgentUtil.getJarSignature(pathResolver.getAbsolutePath(apkInfo.getApkPath()).toString());
    LOG.debug("Local app signature: %s", localAppSignature);
    LOG.debug("Remote app signature: %s", installedAppSignature);

    if (!installedAppSignature.equals(localAppSignature)) {
      LOG.debug("App signatures do not match.  Must re-install.");
      return true;
    }

    LOG.debug("App signatures match.  No need to install.");
    return false;
  }

  private String getInstalledAppSignature(String packagePath) throws Exception {
    try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(eventBus, "get_app_signature")) {
      String output = device.getSignature(packagePath);

      String result = output.trim();
      if (result.contains("\n") || result.contains("\r")) {
        throw new IllegalStateException("Unexpected return from get-signature:\n" + output);
      }

      return result;
    }
  }

  public void installMissingFiles(
      ImmutableSortedSet<Path> presentFiles,
      ImmutableMap<Path, Path> wantedFilesToInstall,
      String filesType)
      throws Exception {
    ImmutableSortedMap<Path, Path> filesToInstall =
        wantedFilesToInstall
            .entrySet()
            .stream()
            .filter(entry -> !presentFiles.contains(entry.getKey()))
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Ordering.natural(), Map.Entry::getKey, Map.Entry::getValue));

    installFiles(filesType, filesToInstall);
  }

  private void deleteUnwantedFiles(
      ImmutableSortedSet<Path> presentFiles, ImmutableSet<Path> wantedFiles) {
    ImmutableSortedSet<Path> filesToDelete =
        presentFiles
            .stream()
            .filter(p -> !p.getFileName().toString().equals("lock") && !wantedFiles.contains(p))
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    deleteFiles(filesToDelete);
  }

  private void deleteFiles(ImmutableSortedSet<Path> filesToDelete) {
    filesToDelete
        .stream()
        .collect(
            ImmutableListMultimap.toImmutableListMultimap(
                path -> dataRoot.resolve(path).getParent(), path -> path.getFileName().toString()))
        .asMap()
        .forEach(
            (dir, files) -> {
              device.rmFiles(dir.toString(), files);
            });
  }

  private void installFiles(String filesType, ImmutableMap<Path, Path> filesToInstall)
      throws Exception {
    try (SimplePerfEvent.Scope ignored =
            SimplePerfEvent.scope(eventBus, "multi_install_" + filesType);
        AutoCloseable ignored1 = device.createForward()) {
      // Make sure all the directories exist.
      filesToInstall
          .keySet()
          .stream()
          .map(p -> dataRoot.resolve(p).getParent())
          .distinct()
          .forEach(
              p -> {
                try {
                  device.mkDirP(p.toString());
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
      // Plan the installation.
      Map<Path, Path> installPaths =
          filesToInstall
              .entrySet()
              .stream()
              .collect(
                  Collectors.toMap(
                      entry -> dataRoot.resolve(entry.getKey()),
                      entry -> projectFilesystem.resolve(entry.getValue())));
      // Install the files.
      device.installFiles(filesType, installPaths);
    }
  }

  private void installMetadata(ImmutableMap<Path, String> metadataToInstall) throws Exception {
    try (Closer closer = Closer.create()) {
      Map<Path, Path> filesToInstall = new HashMap<>();
      for (Map.Entry<Path, String> entry : metadataToInstall.entrySet()) {
        NamedTemporaryFile temp = closer.register(new NamedTemporaryFile("metadata", "tmp"));
        com.google.common.io.Files.write(
            entry.getValue().getBytes(Charsets.UTF_8), temp.get().toFile());
        filesToInstall.put(entry.getKey(), temp.get());
      }
      installFiles("metadata", ImmutableMap.copyOf(filesToInstall));
    }
  }

  /**
   * Parses a text file which is supposed to be in the following format: "file_path_without_spaces
   * file_hash ...." i.e. it parses the first two columns of each line and ignores the rest of it.
   *
   * @return A multi map from the file hash to its path, which equals the raw path resolved against
   *     {@code resolvePathAgainst}.
   */
  @VisibleForTesting
  public static ImmutableMultimap<String, Path> parseExopackageInfoMetadata(
      Path metadataTxt, Path resolvePathAgainst, ProjectFilesystem filesystem) throws IOException {
    ImmutableMultimap.Builder<String, Path> builder = ImmutableMultimap.builder();
    for (String line : filesystem.readLines(metadataTxt)) {
      // ignore lines that start with '.'
      if (line.startsWith(".")) {
        continue;
      }
      List<String> parts = Splitter.on(' ').splitToList(line);
      if (parts.size() < 2) {
        throw new RuntimeException("Illegal line in metadata file: " + line);
      }
      builder.put(parts.get(1), resolvePathAgainst.resolve(parts.get(0)));
    }
    return builder.build();
  }
}
