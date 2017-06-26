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

import com.android.ddmlib.IDevice;
import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.android.HasInstallableApk;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.InstallEvent;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.ExopackageInfo;
import com.facebook.buck.rules.ExopackageInfo.DexInfo;
import com.facebook.buck.rules.ExopackageInfo.NativeLibsInfo;
import com.facebook.buck.rules.ExopackageInfo.ResourcesInfo;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.NamedTemporaryFile;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.io.Closer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** ExopackageInstaller manages the installation of apps with the "exopackage" flag set to true. */
public class ExopackageInstaller {
  private static final Logger LOG = Logger.get(ExopackageInstaller.class);

  @VisibleForTesting public static final Path SECONDARY_DEX_DIR = Paths.get("secondary-dex");

  @VisibleForTesting public static final Path NATIVE_LIBS_DIR = Paths.get("native-libs");

  @VisibleForTesting public static final Path RESOURCES_DIR = Paths.get("resources");

  private static final Pattern LINE_ENDING = Pattern.compile("\r?\n");
  public static final Path EXOPACKAGE_INSTALL_ROOT = Paths.get("/data/local/tmp/exopackage/");

  private final ProjectFilesystem projectFilesystem;
  private final BuckEventBus eventBus;
  private final SourcePathResolver pathResolver;
  private final AdbInterface adbHelper;
  private final HasInstallableApk apkRule;
  private final String packageName;
  private final Path dataRoot;

  private final Optional<ResourcesInfo> resourcesExoInfo;
  private final Optional<NativeLibsInfo> nativeExoInfo;
  private final Optional<DexInfo> dexExoInfo;

  /**
   * AdbInterface provides a way to interact with multiple devices as ExopackageDevices (rather than
   * IDevices).
   *
   * <p>
   *
   * <p>All of ExopackageInstaller's interaction with devices and adb goes through this class and
   * ExopackageDevice making it easy to provide different implementations in tests.
   */
  @VisibleForTesting
  public interface AdbInterface {
    /**
     * This is basically the same as AdbHelper.AdbCallable except that it takes an ExopackageDevice
     * instead of an IDevice.
     */
    interface AdbCallable {
      boolean apply(ExopackageDevice device) throws Exception;
    }

    boolean adbCall(String description, AdbCallable func, boolean quiet)
        throws InterruptedException;
  }

  static class RealAdbInterface implements AdbInterface {
    private AdbHelper adbHelper;
    private BuckEventBus eventBus;
    private Path agentApkPath;

    /**
     * The next port number to use for communicating with the agent on a device. This resets for
     * every instance of RealAdbInterface, but is incremented for every device we are installing on
     * when using "-x".
     */
    private final AtomicInteger nextAgentPort = new AtomicInteger(2828);

    RealAdbInterface(BuckEventBus eventBus, AdbHelper adbHelper, Path agentApkPath) {
      this.eventBus = eventBus;
      this.adbHelper = adbHelper;
      this.agentApkPath = agentApkPath;
    }

    @Override
    public boolean adbCall(String description, AdbCallable func, boolean quiet)
        throws InterruptedException {
      return adbHelper.adbCall(
          new AdbHelper.AdbCallable() {
            @Override
            public boolean call(IDevice device) throws Exception {
              return func.apply(
                  new RealExopackageDevice(
                      eventBus, device, adbHelper, agentApkPath, nextAgentPort.getAndIncrement()));
            }

            @Override
            public String toString() {
              return description;
            }
          },
          quiet);
    }
  }

  private static Path getApkFilePathFromProperties() {
    String apkFileName = System.getProperty("buck.android_agent_path");
    if (apkFileName == null) {
      throw new RuntimeException("Android agent apk path not specified in properties");
    }
    return Paths.get(apkFileName);
  }

  public ExopackageInstaller(
      SourcePathResolver pathResolver,
      ExecutionContext context,
      AdbHelper adbHelper,
      HasInstallableApk apkRule) {
    this(
        pathResolver,
        context,
        new RealAdbInterface(context.getBuckEventBus(), adbHelper, getApkFilePathFromProperties()),
        apkRule);
  }

  public ExopackageInstaller(
      SourcePathResolver pathResolver,
      ExecutionContext context,
      AdbInterface adbInterface,
      HasInstallableApk apkRule) {
    this.pathResolver = pathResolver;
    this.adbHelper = adbInterface;
    this.projectFilesystem = apkRule.getProjectFilesystem();
    this.eventBus = context.getBuckEventBus();
    this.apkRule = apkRule;
    this.packageName =
        AdbHelper.tryToExtractPackageNameFromManifest(pathResolver, apkRule.getApkInfo());
    this.dataRoot = EXOPACKAGE_INSTALL_ROOT.resolve(packageName);

    Preconditions.checkArgument(AdbHelper.PACKAGE_NAME_PATTERN.matcher(packageName).matches());

    Optional<ExopackageInfo> exopackageInfo = apkRule.getApkInfo().getExopackageInfo();
    this.nativeExoInfo =
        exopackageInfo.map(ExopackageInfo::getNativeLibsInfo).orElse(Optional.empty());
    this.dexExoInfo = exopackageInfo.map(ExopackageInfo::getDexInfo).orElse(Optional.empty());
    this.resourcesExoInfo =
        exopackageInfo.map(ExopackageInfo::getResourcesInfo).orElse(Optional.empty());
  }

  /** Installs the app specified in the constructor. This object should be discarded afterward. */
  public synchronized boolean install(boolean quiet) throws InterruptedException {
    InstallEvent.Started started = InstallEvent.started(apkRule.getBuildTarget());
    eventBus.post(started);

    boolean success =
        adbHelper.adbCall(
            "install exopackage apk",
            device -> new SingleDeviceInstaller(device).doInstall(),
            quiet);

    eventBus.post(
        InstallEvent.finished(
            started,
            success,
            Optional.empty(),
            Optional.of(
                AdbHelper.tryToExtractPackageNameFromManifest(
                    pathResolver, apkRule.getApkInfo()))));
    return success;
  }

  /** Helper class to manage the state required to install on a single device. */
  private class SingleDeviceInstaller {

    /** Device that we are installing onto. */
    private final ExopackageDevice device;

    private SingleDeviceInstaller(ExopackageDevice device) {
      this.device = device;
    }

    boolean doInstall() throws Exception {
      if (exopackageEnabled()) {
        device.mkDirP(dataRoot.toString());
        ImmutableSortedSet<Path> presentFiles = device.listDirRecursive(dataRoot);
        ImmutableSet.Builder<Path> wantedPaths = ImmutableSet.builder();
        ImmutableMap.Builder<Path, String> metadata = ImmutableMap.builder();

        if (dexExoInfo.isPresent()) {
          DexExoHelper dexExoHelper =
              new DexExoHelper(pathResolver, projectFilesystem, dexExoInfo.get());
          installMissingFiles(presentFiles, dexExoHelper.getFilesToInstall(), "secondary_dex");
          wantedPaths.addAll(dexExoHelper.getFilesToInstall().keySet());
          metadata.putAll(dexExoHelper.getMetadataToInstall());
        }

        if (nativeExoInfo.isPresent()) {
          NativeExoHelper nativeExoHelper =
              new NativeExoHelper(device, pathResolver, projectFilesystem, nativeExoInfo.get());
          installMissingFiles(presentFiles, nativeExoHelper.getFilesToInstall(), "native_library");
          wantedPaths.addAll(nativeExoHelper.getFilesToInstall().keySet());
          metadata.putAll(nativeExoHelper.getMetadataToInstall());
        }

        if (resourcesExoInfo.isPresent()) {
          ResourcesExoHelper resourcesExoHelper =
              new ResourcesExoHelper(pathResolver, projectFilesystem, resourcesExoInfo.get());
          installMissingFiles(presentFiles, resourcesExoHelper.getFilesToInstall(), "resources");
          wantedPaths.addAll(resourcesExoHelper.getFilesToInstall().keySet());
          metadata.putAll(resourcesExoHelper.getMetadataToInstall());
        }

        deleteUnwantedFiles(presentFiles, wantedPaths.build());
        installMetadata(metadata.build());
      }

      final File apk = pathResolver.getAbsolutePath(apkRule.getApkInfo().getApkPath()).toFile();
      // TODO(dreiss): Support SD installation.
      final boolean installViaSd = false;

      if (shouldAppBeInstalled()) {
        try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(eventBus, "install_exo_apk")) {
          boolean success = device.installApkOnDevice(apk, installViaSd, false);
          if (!success) {
            return false;
          }
        }
      }
      // TODO(dreiss): Make this work on Gingerbread.
      try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(eventBus, "kill_app")) {
        device.stopPackage(packageName);
      }

      return true;
    }

    private boolean exopackageEnabled() {
      return dexExoInfo.isPresent() || nativeExoInfo.isPresent() || resourcesExoInfo.isPresent();
    }

    private Optional<PackageInfo> getPackageInfo(final String packageName) throws Exception {
      try (SimplePerfEvent.Scope ignored =
          SimplePerfEvent.scope(
              eventBus, PerfEventId.of("get_package_info"), "package", packageName)) {
        return device.getPackageInfo(packageName);
      }
    }

    private boolean shouldAppBeInstalled() throws Exception {
      Optional<PackageInfo> appPackageInfo = getPackageInfo(packageName);
      if (!appPackageInfo.isPresent()) {
        eventBus.post(ConsoleEvent.info("App not installed.  Installing now."));
        return true;
      }

      LOG.debug("App path: %s", appPackageInfo.get().apkPath);
      String installedAppSignature = getInstalledAppSignature(appPackageInfo.get().apkPath);
      String localAppSignature =
          AgentUtil.getJarSignature(
              pathResolver.getAbsolutePath(apkRule.getApkInfo().getApkPath()).toString());
      LOG.debug("Local app signature: %s", localAppSignature);
      LOG.debug("Remote app signature: %s", installedAppSignature);

      if (!installedAppSignature.equals(localAppSignature)) {
        LOG.debug("App signatures do not match.  Must re-install.");
        return true;
      }

      LOG.debug("App signatures match.  No need to install.");
      return false;
    }

    private String getInstalledAppSignature(final String packagePath) throws Exception {
      try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(eventBus, "get_app_signature")) {
        String output = device.getSignature(packagePath);

        String result = output.trim();
        if (result.contains("\n") || result.contains("\r")) {
          throw new IllegalStateException("Unexpected return from get-signature:\n" + output);
        }

        return result;
      }
    }

    private void installMissingFiles(
        ImmutableSortedSet<Path> presentFiles,
        ImmutableMap<Path, Path> wantedFilesToInstall,
        String filesType)
        throws Exception {
      ImmutableSortedMap<Path, Path> filesToInstall =
          wantedFilesToInstall
              .entrySet()
              .stream()
              .filter(entry -> !presentFiles.contains(entry.getKey()))
              .collect(MoreCollectors.toImmutableSortedMap(Map.Entry::getKey, Map.Entry::getValue));

      installFiles(filesType, filesToInstall);
    }

    private void deleteUnwantedFiles(
        ImmutableSortedSet<Path> presentFiles, ImmutableSet<Path> wantedFiles) {
      ImmutableSortedSet<Path> filesToDelete =
          presentFiles
              .stream()
              .filter(p -> !p.getFileName().equals("lock") && !wantedFiles.contains(p))
              .collect(MoreCollectors.toImmutableSortedSet());
      deleteFiles(filesToDelete);
    }

    private void deleteFiles(ImmutableSortedSet<Path> filesToDelete) {
      filesToDelete
          .stream()
          .collect(
              MoreCollectors.toImmutableListMultimap(
                  path -> dataRoot.resolve(path).getParent(),
                  path -> path.getFileName().toString()))
          .asMap()
          .forEach(
              (dir, files) -> {
                try {
                  device.rmFiles(dir.toString(), files);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
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
        // Install the files.
        filesToInstall.forEach(
            (devicePath, hostPath) -> {
              Path destination = dataRoot.resolve(devicePath);
              Path source = projectFilesystem.resolve(hostPath);
              try (SimplePerfEvent.Scope ignored2 =
                  SimplePerfEvent.scope(eventBus, "install_" + filesType)) {
                device.installFile(destination, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
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

  @VisibleForTesting
  public static Optional<PackageInfo> parsePathAndPackageInfo(
      String packageName, String rawOutput) {
    Iterable<String> lines = Splitter.on(LINE_ENDING).omitEmptyStrings().split(rawOutput);
    String pmPathPrefix = "package:";

    String pmPath = null;
    for (String line : lines) {
      // Ignore silly linker warnings about non-PIC code on emulators
      if (!line.startsWith("WARNING: linker: ")) {
        pmPath = line;
        break;
      }
    }

    if (pmPath == null || !pmPath.startsWith(pmPathPrefix)) {
      LOG.warn("unable to locate package path for [" + packageName + "]");
      return Optional.empty();
    }

    final String packagePrefix = "  Package [" + packageName + "] (";
    final String otherPrefix = "  Package [";
    boolean sawPackageLine = false;
    final Splitter splitter = Splitter.on('=').limit(2);

    String codePath = null;
    String resourcePath = null;
    String nativeLibPath = null;
    String versionCode = null;

    for (String line : lines) {
      // Just ignore everything until we see the line that says we are in the right package.
      if (line.startsWith(packagePrefix)) {
        sawPackageLine = true;
        continue;
      }
      // This should never happen, but if we do see a different package, stop parsing.
      if (line.startsWith(otherPrefix)) {
        break;
      }
      // Ignore lines before our package.
      if (!sawPackageLine) {
        continue;
      }
      // Parse key-value pairs.
      List<String> parts = splitter.splitToList(line.trim());
      if (parts.size() != 2) {
        continue;
      }
      switch (parts.get(0)) {
        case "codePath":
          codePath = parts.get(1);
          break;
        case "resourcePath":
          resourcePath = parts.get(1);
          break;
        case "nativeLibraryPath":
          nativeLibPath = parts.get(1);
          break;
          // Lollipop uses this name.  Not sure what's "legacy" about it yet.
          // Maybe something to do with 64-bit?
          // Might need to update if people report failures.
        case "legacyNativeLibraryDir":
          nativeLibPath = parts.get(1);
          break;
        case "versionCode":
          // Extra split to get rid of the SDK thing.
          versionCode = parts.get(1).split(" ", 2)[0];
          break;
        default:
          break;
      }
    }

    if (!sawPackageLine) {
      return Optional.empty();
    }

    Preconditions.checkNotNull(codePath, "Could not find codePath");
    Preconditions.checkNotNull(resourcePath, "Could not find resourcePath");
    Preconditions.checkNotNull(nativeLibPath, "Could not find nativeLibraryPath");
    Preconditions.checkNotNull(versionCode, "Could not find versionCode");
    if (!codePath.equals(resourcePath)) {
      throw new IllegalStateException("Code and resource path do not match");
    }

    // Lollipop doesn't give the full path to the apk anymore.  Not sure why it's "base.apk".
    if (!codePath.endsWith(".apk")) {
      codePath += "/base.apk";
    }

    return Optional.of(new PackageInfo(codePath, nativeLibPath, versionCode));
  }
}
