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
import com.facebook.buck.rules.ExopackageInfo.ResourcesInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.NamedTemporaryFile;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ExopackageInstaller manages the installation of apps with the "exopackage" flag set to true. */
public class ExopackageInstaller {
  private static final Logger LOG = Logger.get(ExopackageInstaller.class);

  @VisibleForTesting public static final Path SECONDARY_DEX_DIR = Paths.get("secondary-dex");

  @VisibleForTesting public static final Path NATIVE_LIBS_DIR = Paths.get("native-libs");

  @VisibleForTesting public static final Path RESOURCES_DIR = Paths.get("resources");

  @VisibleForTesting
  public static final Pattern DEX_FILE_PATTERN =
      Pattern.compile("secondary-([0-9a-f]+)\\.[\\w.-]*");

  @VisibleForTesting
  public static final Pattern NATIVE_LIB_PATTERN = Pattern.compile("native-([0-9a-f]+)\\.so");

  @VisibleForTesting
  public static final Pattern RESOURCES_FILE_PATTERN = Pattern.compile("([0-9a-f]+)\\.apk");

  private static final Pattern LINE_ENDING = Pattern.compile("\r?\n");
  public static final Path EXOPACKAGE_INSTALL_ROOT = Paths.get("/data/local/tmp/exopackage/");

  private final ProjectFilesystem projectFilesystem;
  private final BuckEventBus eventBus;
  private final SourcePathResolver pathResolver;
  private final AdbInterface adbHelper;
  private final HasInstallableApk apkRule;
  private final String packageName;
  private final Path dataRoot;

  private final ExopackageInfo exopackageInfo;

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
    Preconditions.checkArgument(exopackageInfo.isPresent());
    this.exopackageInfo = exopackageInfo.get();
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

      // TODO(cjhopman): We should clear out the directories on the device for types we don't
      // install.
      if (exopackageInfo.getDexInfo().isPresent()) {
        installSecondaryDexFiles();
      }

      if (exopackageInfo.getNativeLibsInfo().isPresent()) {
        installNativeLibraryFiles();
      }

      if (exopackageInfo.getResourcesInfo().isPresent()) {
        installResourcesFiles();
      }

      // TODO(dreiss): Make this work on Gingerbread.
      try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(eventBus, "kill_app")) {
        device.stopPackage(packageName);
      }

      return true;
    }

    private void installSecondaryDexFiles() throws Exception {
      final ImmutableMap<String, Path> hashToSources = getRequiredDexFiles();
      final ImmutableSet<String> requiredHashes = hashToSources.keySet();
      final ImmutableSet<String> presentHashes = prepareSecondaryDexDir(requiredHashes);
      final Set<String> hashesToInstall = Sets.difference(requiredHashes, presentHashes);

      Map<String, Path> filesToInstallByHash =
          Maps.filterKeys(hashToSources, hashesToInstall::contains);

      // This is a bit gross.  It was a late addition.  Ideally, we could eliminate this, but
      // it wouldn't be terrible if we don't.  We store the dexed jars on the device
      // with the full SHA-1 hashes in their names.  This is the format that the loader uses
      // internally, so ideally we would just load them in place.  However, the code currently
      // expects to be able to copy the jars from a directory that matches the name in the
      // metadata file, like "secondary-1.dex.jar".  We don't want to give up putting the
      // hashes in the file names (because we use that to skip re-uploads), so just hack
      // the metadata file to have hash-like names.
      String metadataContents =
          com.google.common.io.Files.toString(
                  projectFilesystem
                      .resolve(exopackageInfo.getDexInfo().get().getMetadata())
                      .toFile(),
                  Charsets.UTF_8)
              .replaceAll(
                  "secondary-(\\d+)\\.dex\\.jar (\\p{XDigit}{40}) ", "secondary-$2.dex.jar $2 ");

      ImmutableMap<Path, Path> filesToInstall =
          applyFilenameFormat(filesToInstallByHash, SECONDARY_DEX_DIR, "secondary-%s.dex.jar");
      installFiles("secondary_dex", filesToInstall);
      installMetadata(ImmutableMap.of(SECONDARY_DEX_DIR.resolve("metadata.txt"), metadataContents));
    }

    private void installResourcesFiles() throws Exception {
      ResourcesInfo info = exopackageInfo.getResourcesInfo().get();

      ImmutableMap.Builder<String, Path> hashToSourcesBuilder = ImmutableMap.builder();
      String metadataContent = "";
      String prefix = "";
      for (SourcePath sourcePath : info.getResourcesPaths()) {
        Path path = pathResolver.getRelativePath(sourcePath);
        String hash = projectFilesystem.computeSha1(path).getHash();
        metadataContent += prefix + "resources " + hash;
        prefix = "\n";
        hashToSourcesBuilder.put(hash, path);
      }

      final ImmutableMap<String, Path> hashToSources = hashToSourcesBuilder.build();
      final ImmutableSet<String> requiredHashes = hashToSources.keySet();
      final ImmutableSet<String> presentHashes = prepareResourcesDir(requiredHashes);
      final Set<String> hashesToInstall = Sets.difference(requiredHashes, presentHashes);

      Map<String, Path> filesToInstallByHash =
          Maps.filterKeys(hashToSources, hashesToInstall::contains);

      ImmutableMap<Path, Path> filesToInstall =
          applyFilenameFormat(filesToInstallByHash, RESOURCES_DIR, "%s.apk");
      installFiles("resources", filesToInstall);
      installMetadata(ImmutableMap.of(RESOURCES_DIR.resolve("metadata.txt"), metadataContent));
    }

    private void installNativeLibraryFiles() throws Exception {
      ImmutableMultimap<String, Path> allLibraries = getAllLibraries();
      ImmutableSet.Builder<String> providedLibraries = ImmutableSet.builder();
      for (String abi : device.getDeviceAbis()) {
        ImmutableMap<String, Path> libraries =
            getRequiredLibrariesForAbi(allLibraries, abi, providedLibraries.build());
        installNativeLibrariesForAbi(abi, libraries);
        providedLibraries.addAll(libraries.keySet());
      }
    }

    private void installNativeLibrariesForAbi(String abi, ImmutableMap<String, Path> libraries)
        throws Exception {
      if (libraries.isEmpty()) {
        return;
      }

      String metadataContents =
          Joiner.on('\n')
              .join(
                  FluentIterable.from(libraries.entrySet())
                      .transform(
                          input -> {
                            String hash = input.getKey();
                            String filename = input.getValue().getFileName().toString();
                            int index = filename.indexOf('.');
                            String libname = index == -1 ? filename : filename.substring(0, index);
                            return String.format("%s native-%s.so", libname, hash);
                          }));

      ImmutableSet<String> requiredHashes = libraries.keySet();
      ImmutableSet<String> presentHashes = prepareNativeLibsDir(abi, requiredHashes);

      Map<String, Path> filesToInstallByHash =
          Maps.filterKeys(libraries, Predicates.not(presentHashes::contains));

      Path abiDir = NATIVE_LIBS_DIR.resolve(abi);
      ImmutableMap<Path, Path> filesToInstall =
          applyFilenameFormat(filesToInstallByHash, abiDir, "native-%s.so");
      installFiles("native_library", filesToInstall);
      installMetadata(ImmutableMap.of(abiDir.resolve("metadata.txt"), metadataContents));
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

    private ImmutableMap<String, Path> getRequiredDexFiles() throws IOException {
      ExopackageInfo.DexInfo dexInfo = exopackageInfo.getDexInfo().get();
      ImmutableMultimap<String, Path> multimap =
          parseExopackageInfoMetadata(
              dexInfo.getMetadata(), dexInfo.getDirectory(), projectFilesystem);
      // Convert multimap to a map, because every key should have only one value.
      ImmutableMap.Builder<String, Path> builder = ImmutableMap.builder();
      for (Map.Entry<String, Path> entry : multimap.entries()) {
        builder.put(entry);
      }
      return builder.build();
    }

    private ImmutableSet<String> prepareSecondaryDexDir(ImmutableSet<String> requiredHashes)
        throws Exception {
      return prepareDirectory(SECONDARY_DEX_DIR, DEX_FILE_PATTERN, requiredHashes);
    }

    private ImmutableSet<String> prepareNativeLibsDir(
        String abi, ImmutableSet<String> requiredHashes) throws Exception {
      return prepareDirectory(NATIVE_LIBS_DIR.resolve(abi), NATIVE_LIB_PATTERN, requiredHashes);
    }

    private ImmutableSet<String> prepareResourcesDir(ImmutableSet<String> requiredHashes)
        throws Exception {
      return prepareDirectory(RESOURCES_DIR, RESOURCES_FILE_PATTERN, requiredHashes);
    }

    private ImmutableSet<String> prepareDirectory(
        Path dirname, Pattern filePattern, ImmutableSet<String> requiredHashes) throws Exception {
      try (SimplePerfEvent.Scope ignored = SimplePerfEvent.scope(eventBus, "prepare_" + dirname)) {
        String dirPath = dataRoot.resolve(dirname).toString();
        device.mkDirP(dirPath);

        String output = device.listDir(dirPath);

        ImmutableSet.Builder<String> foundHashes = ImmutableSet.builder();
        ImmutableSet.Builder<String> filesToDelete = ImmutableSet.builder();

        processLsOutput(output, filePattern, requiredHashes, foundHashes, filesToDelete);

        device.rmFiles(dirPath, filesToDelete.build());
        return foundHashes.build();
      }
    }

    private ImmutableMap<Path, Path> applyFilenameFormat(
        Map<String, Path> filesToHashes, Path deviceDir, String filenameFormat) {
      ImmutableMap.Builder<Path, Path> filesBuilder = ImmutableMap.builder();
      for (Map.Entry<String, Path> entry : filesToHashes.entrySet()) {
        filesBuilder.put(
            deviceDir.resolve(String.format(filenameFormat, entry.getKey())), entry.getValue());
      }
      return filesBuilder.build();
    }

    private void installFiles(String filesType, ImmutableMap<Path, Path> filesToInstall)
        throws Exception {
      try (SimplePerfEvent.Scope ignored =
              SimplePerfEvent.scope(eventBus, "multi_install_" + filesType);
          AutoCloseable ignored1 = device.createForward()) {
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

  private ImmutableMultimap<String, Path> getAllLibraries() throws IOException {
    ExopackageInfo.NativeLibsInfo nativeLibsInfo = exopackageInfo.getNativeLibsInfo().get();
    return parseExopackageInfoMetadata(
        nativeLibsInfo.getMetadata(), nativeLibsInfo.getDirectory(), projectFilesystem);
  }

  private ImmutableMap<String, Path> getRequiredLibrariesForAbi(
      ImmutableMultimap<String, Path> allLibraries,
      String abi,
      ImmutableSet<String> ignoreLibraries) {
    return filterLibrariesForAbi(
        exopackageInfo.getNativeLibsInfo().get().getDirectory(),
        allLibraries,
        abi,
        ignoreLibraries);
  }

  @VisibleForTesting
  public static ImmutableMap<String, Path> filterLibrariesForAbi(
      Path nativeLibsDir,
      ImmutableMultimap<String, Path> allLibraries,
      String abi,
      ImmutableSet<String> ignoreLibraries) {
    ImmutableMap.Builder<String, Path> filteredLibraries = ImmutableMap.builder();
    for (Map.Entry<String, Path> entry : allLibraries.entries()) {
      Path relativePath = nativeLibsDir.relativize(entry.getValue());
      // relativePath is of the form libs/x86/foo.so, or assetLibs/x86/foo.so etc.
      Preconditions.checkState(relativePath.getNameCount() == 3);
      Preconditions.checkState(
          relativePath.getName(0).toString().equals("libs")
              || relativePath.getName(0).toString().equals("assetLibs"));
      String libAbi = relativePath.getParent().getFileName().toString();
      String libName = relativePath.getFileName().toString();
      if (libAbi.equals(abi) && !ignoreLibraries.contains(libName)) {
        filteredLibraries.put(entry);
      }
    }
    return filteredLibraries.build();
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

  /**
   * @param output Output of "ls" command.
   * @param filePattern A {@link Pattern} that is used to check if a file is valid, and if it
   *     matches, {@code filePattern.group(1)} should return the hash in the file name.
   * @param requiredHashes Hashes of dex files required for this apk.
   * @param foundHashes Builder to receive hashes that we need and were found.
   * @param toDelete Builder to receive files that we need to delete.
   */
  @VisibleForTesting
  public static void processLsOutput(
      String output,
      Pattern filePattern,
      ImmutableSet<String> requiredHashes,
      ImmutableSet.Builder<String> foundHashes,
      ImmutableSet.Builder<String> toDelete) {
    for (String line : Splitter.on(LINE_ENDING).omitEmptyStrings().split(output)) {
      if (line.equals("lock")) {
        continue;
      }

      Matcher m = filePattern.matcher(line);
      if (m.matches()) {
        if (requiredHashes.contains(m.group(1))) {
          foundHashes.add(m.group(1));
        } else {
          toDelete.add(line);
        }
      } else {
        toDelete.add(line);
      }
    }
  }
}
