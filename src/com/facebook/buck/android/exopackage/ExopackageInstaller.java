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

package com.facebook.buck.android.exopackage;

import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.android.HasInstallableApk;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.NamedTemporaryFile;
import com.google.common.annotations.VisibleForTesting;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** ExopackageInstaller manages the installation of apps with the "exopackage" flag set to true. */
public class ExopackageInstaller {
  private static final Logger LOG = Logger.get(ExopackageInstaller.class);

  // When there are a small number of files to delete, it's faster (we issue
  // fewer rm commands over adb) if we group them by the data root, so
  //   cd /data/local/tmp/exopackage/foo && rm resources/bar.txt native-libs/baz.txt
  // But when there are more files, because of the way RealAndroidDevice splits up
  // commandlines to avoid hitting limits, it's more efficient to group by the
  // subdirectories, so
  //   cd /data/local/tmp/exopackage/foo/resources && rm bar.txt bap.txt boz.txt
  //   cd /data/local/tmp/exopackage/foo/native-libs && rm baz.txt zog.txt
  // We pick a heuristic number of files (10) at which to change behavior for this based
  // on these assumptions:
  //    approx available commandline length = 800
  //    max length of a path from the dataRoot for a well known app = 77
  private static final int RM_GROUPING_THRESHOLD = 10;

  public static final Path EXOPACKAGE_INSTALL_ROOT = Paths.get("/data/local/tmp/exopackage/");

  private final ProjectFilesystem projectFilesystem;
  private final BuckEventBus eventBus;
  private final SourcePathResolverAdapter pathResolver;
  private final AndroidDevice device;
  private final String packageName;
  private final Path dataRoot;
  private final boolean skipMetadataIfNoInstalls;

  public ExopackageInstaller(
      SourcePathResolverAdapter pathResolver,
      BuckEventBus eventBus,
      ProjectFilesystem projectFilesystem,
      String packageName,
      AndroidDevice device,
      boolean skipMetadataIfNoInstalls) {
    this.pathResolver = pathResolver;
    this.projectFilesystem = projectFilesystem;
    this.eventBus = eventBus;
    this.device = device;
    this.packageName = packageName;
    this.dataRoot = EXOPACKAGE_INSTALL_ROOT.resolve(packageName);
    this.skipMetadataIfNoInstalls = skipMetadataIfNoInstalls;

    Preconditions.checkArgument(AdbHelper.PACKAGE_NAME_PATTERN.matcher(packageName).matches());
  }

  /** Installs an apk, restarting the running app if necessary. */
  public void doInstall(HasInstallableApk.ApkInfo apkInfo) throws Exception {
    if (exopackageEnabled(apkInfo)) {
      device.mkDirP(dataRoot.toString());
      ImmutableSortedSet<Path> presentFiles = device.listDirRecursive(dataRoot);
      ExopackageInfo exoInfo = apkInfo.getExopackageInfo().get();
      installMissingExopackageFiles(presentFiles, exoInfo);
      finishExoFileInstallation(presentFiles, exoInfo);
    }
    installAndRestartApk(apkInfo);
  }

  public void installAndRestartApk(HasInstallableApk.ApkInfo apkInfo) throws Exception {
    installApkIfNecessary(apkInfo);
    killApp();
  }

  private void installApkIfNecessary(HasInstallableApk.ApkInfo apkInfo) throws Exception {
    File apk = pathResolver.getAbsolutePath(apkInfo.getApkPath()).toFile();

    if (shouldAppBeInstalled(apkInfo)) {
      try (SimplePerfEvent.Scope ignored =
          SimplePerfEvent.scope(eventBus.isolated(), "install_exo_apk")) {
        boolean success = device.installApkOnDevice(apk, /*installViaSd=*/ false, false);
        if (!success) {
          throw new RuntimeException("Installing Apk failed.");
        }
      }
    }
  }

  private void killApp() throws Exception {
    try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(eventBus.isolated(), "kill_app")) {
      device.stopPackage(packageName);
    }
  }

  private void addPaths(ExoHelper helper, ImmutableSet.Builder<Path> wantedPaths) throws Exception {
    wantedPaths.addAll(helper.getFilesToInstall().keySet());
    wantedPaths.addAll(helper.getMetadataToInstall().keySet());
  }

  public void finishExoFileInstallation(
      ImmutableSortedSet<Path> presentFiles, ExopackageInfo exoInfo) throws Exception {
    ImmutableSet.Builder<Path> wantedPaths = ImmutableSet.builder();

    if (exoInfo.getDexInfo().isPresent()) {
      DexExoHelper helper =
          new DexExoHelper(pathResolver, projectFilesystem, exoInfo.getDexInfo().get());
      addPaths(helper, wantedPaths);
    }

    if (exoInfo.getNativeLibsInfo().isPresent()) {
      NativeExoHelper helper =
          new NativeExoHelper(
              () -> {
                try {
                  return device.getDeviceAbis();
                } catch (Exception e) {
                  throw new HumanReadableException("Unable to communicate with device", e);
                }
              },
              pathResolver,
              projectFilesystem,
              exoInfo.getNativeLibsInfo().get());
      addPaths(helper, wantedPaths);
    }

    if (exoInfo.getResourcesInfo().isPresent()) {
      ResourcesExoHelper helper =
          new ResourcesExoHelper(pathResolver, projectFilesystem, exoInfo.getResourcesInfo().get());
      addPaths(helper, wantedPaths);
    }

    if (exoInfo.getModuleInfo().isPresent()) {
      ModuleExoHelper helper =
          new ModuleExoHelper(pathResolver, projectFilesystem, exoInfo.getModuleInfo().get());
      addPaths(helper, wantedPaths);
    }

    deleteUnwantedFiles(presentFiles, wantedPaths.build());
  }

  public void installMissingExopackageFiles(
      ImmutableSortedSet<Path> presentFiles, ExopackageInfo exoInfo) throws Exception {

    ImmutableMap.Builder<Path, String> metadata = ImmutableMap.builder();

    if (exoInfo.getDexInfo().isPresent()) {
      DexExoHelper dexExoHelper =
          new DexExoHelper(pathResolver, projectFilesystem, exoInfo.getDexInfo().get());
      installMissingFiles(presentFiles, dexExoHelper, metadata);
    }

    if (exoInfo.getNativeLibsInfo().isPresent()) {
      NativeExoHelper nativeExoHelper =
          new NativeExoHelper(
              () -> {
                try {
                  return device.getDeviceAbis();
                } catch (Exception e) {
                  throw new HumanReadableException("Unable to communicate with device", e);
                }
              },
              pathResolver,
              projectFilesystem,
              exoInfo.getNativeLibsInfo().get());
      installMissingFiles(presentFiles, nativeExoHelper, metadata);
    }

    if (exoInfo.getResourcesInfo().isPresent()) {
      ResourcesExoHelper resourcesExoHelper =
          new ResourcesExoHelper(pathResolver, projectFilesystem, exoInfo.getResourcesInfo().get());
      installMissingFiles(presentFiles, resourcesExoHelper, metadata);
    }

    if (exoInfo.getModuleInfo().isPresent()) {
      ModuleExoHelper moduleExoHelper =
          new ModuleExoHelper(pathResolver, projectFilesystem, exoInfo.getModuleInfo().get());
      installMissingFiles(presentFiles, moduleExoHelper, metadata);
    }

    installMetadata(metadata.build());
  }

  /**
   * @param apkInfo the apk info to examine for exopackage items
   * @return true if the given apk info contains any items which need to be installed via exopackage
   */
  public static boolean exopackageEnabled(HasInstallableApk.ApkInfo apkInfo) {
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
            eventBus.isolated(),
            SimplePerfEvent.PerfEventId.of("get_package_info"),
            "package",
            packageName)) {
      return device.getPackageInfo(packageName);
    }
  }

  private boolean shouldAppBeInstalled(HasInstallableApk.ApkInfo apkInfo) throws Exception {
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
    try (SimplePerfEvent.Scope ignored =
        SimplePerfEvent.scope(eventBus.isolated(), "get_app_signature")) {
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
      ExoHelper helper,
      @Nullable ImmutableMap.Builder<Path, String> metadataToInstall)
      throws Exception {
    ImmutableSortedMap<Path, Path> filesToInstall =
        helper.getFilesToInstall().entrySet().stream()
            .filter(entry -> !presentFiles.contains(entry.getKey()))
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Ordering.natural(), Map.Entry::getKey, Map.Entry::getValue));

    installFiles(helper.getType(), filesToInstall);

    if (metadataToInstall != null && (!skipMetadataIfNoInstalls || !filesToInstall.isEmpty())) {
      metadataToInstall.putAll(helper.getMetadataToInstall());
    }
  }

  private void deleteUnwantedFiles(
      ImmutableSortedSet<Path> presentFiles, ImmutableSet<Path> wantedFiles) {
    ImmutableSortedSet<Path> filesToDelete =
        presentFiles.stream()
            .filter(p -> !p.getFileName().toString().equals("lock") && !wantedFiles.contains(p))
            .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
    deleteFiles(filesToDelete);
  }

  private void deleteFiles(ImmutableSortedSet<Path> filesToDelete) {
    Function<Path, Path> toRootDirFn =
        filesToDelete.size() <= RM_GROUPING_THRESHOLD
            ? path -> dataRoot
            : path -> dataRoot.resolve(path).getParent();
    Function<Path, String> toFileFn =
        filesToDelete.size() <= RM_GROUPING_THRESHOLD
            ? path -> path.toString()
            : path -> path.getFileName().toString();

    filesToDelete.stream()
        .collect(ImmutableListMultimap.toImmutableListMultimap(toRootDirFn, toFileFn))
        .asMap()
        .forEach(
            (dir, files) -> {
              device.rmFiles(dir.toString(), files);
            });
  }

  private void installFiles(String filesType, ImmutableMap<Path, Path> filesToInstall)
      throws Exception {
    try (SimplePerfEvent.Scope ignored =
            SimplePerfEvent.scope(eventBus.isolated(), "multi_install_" + filesType);
        AutoCloseable ignored1 = device.createForward()) {
      // Make sure all the directories exist.
      filesToInstall.keySet().stream()
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
          filesToInstall.entrySet().stream()
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
            entry.getValue().getBytes(StandardCharsets.UTF_8), temp.get().toFile());
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

  public static ImmutableMultimap<String, Path> parseExopackageInfoMetadata(
      AbsPath metadataTxt, AbsPath resolvePathAgainst, ProjectFilesystem filesystem)
      throws IOException {
    return parseExopackageInfoMetadata(
        metadataTxt.getPath(), resolvePathAgainst.getPath(), filesystem);
  }
}
