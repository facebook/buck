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

package com.facebook.buck.cli;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.TraceEventLogger;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.ExopackageInfo;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.NamedTemporaryFile;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * ExopackageInstaller manages the installation of apps with the "exopackage" flag set to true.
 */
public class ExopackageInstaller {

  private static final Logger LOG = Logger.get(ExopackageInstaller.class);

  /**
   * Prefix of the path to the agent apk on the device.
   */
  private static final String AGENT_DEVICE_PATH = "/data/app/" + AgentUtil.AGENT_PACKAGE_NAME;

  /**
   * Command line to invoke the agent on the device.
   */
  private static final String JAVA_AGENT_COMMAND =
      "dalvikvm -classpath " +
      AGENT_DEVICE_PATH + "-1.apk:" + AGENT_DEVICE_PATH + "-2.apk:" +
      AGENT_DEVICE_PATH + "-1/base.apk:" + AGENT_DEVICE_PATH + "-2/base.apk " +
      "com.facebook.buck.android.agent.AgentMain ";

  /**
   * Maximum length of commands that can be passed to "adb shell".
   */
  private static final int MAX_ADB_COMMAND_SIZE = 1019;

  private static final Path SECONDARY_DEX_DIR = Paths.get("secondary-dex");

  private static final Path NATIVE_LIBS_DIR = Paths.get("native-libs");

  @VisibleForTesting
  static final Pattern DEX_FILE_PATTERN = Pattern.compile("secondary-([0-9a-f]+)\\.[\\w.-]*");

  @VisibleForTesting
  static final Pattern NATIVE_LIB_PATTERN = Pattern.compile("native-([0-9a-f]+)\\.so");

  private final ProjectFilesystem projectFilesystem;
  private final BuckEventBus eventBus;
  private final AdbHelper adbHelper;
  private final InstallableApk apkRule;
  private final String packageName;
  private final Path dataRoot;

  private final ExopackageInfo exopackageInfo;

  /**
   * The next port number to use for communicating with the agent on a device.
   * This resets for every instance of ExopackageInstaller,
   * but is incremented for every device we are installing on when using "-x".
   */
  private final AtomicInteger nextAgentPort = new AtomicInteger(2828);

  @VisibleForTesting
  static class PackageInfo {
    final String apkPath;
    final String nativeLibPath;
    final String versionCode;
    private PackageInfo(String apkPath, String nativeLibPath, String versionCode) {
      this.nativeLibPath = nativeLibPath;
      this.apkPath = apkPath;
      this.versionCode = versionCode;
    }
  }

  public ExopackageInstaller(
      ExecutionContext context,
      AdbHelper adbHelper,
      InstallableApk apkRule) {
    this.adbHelper = adbHelper;
    this.projectFilesystem = context.getProjectFilesystem();
    this.eventBus = context.getBuckEventBus();
    this.apkRule = apkRule;
    this.packageName = AdbHelper.tryToExtractPackageNameFromManifest(apkRule, context);
    this.dataRoot = Paths.get("/data/local/tmp/exopackage/").resolve(packageName);

    Preconditions.checkArgument(AdbHelper.PACKAGE_NAME_PATTERN.matcher(packageName).matches());

    Optional<ExopackageInfo> exopackageInfo = apkRule.getExopackageInfo();
    Preconditions.checkArgument(exopackageInfo.isPresent());
    this.exopackageInfo = exopackageInfo.get();
  }

  /**
   * Installs the app specified in the constructor.  This object should be discarded afterward.
   */
  public synchronized boolean install() throws InterruptedException {
    eventBus.post(InstallEvent.started(apkRule.getBuildTarget()));

    boolean success = adbHelper.adbCall(
        new AdbHelper.AdbCallable() {
          @Override
          public boolean call(IDevice device) throws Exception {
            try {
              return new SingleDeviceInstaller(device, nextAgentPort.getAndIncrement()).doInstall();
            } catch (Exception e) {
              throw new RuntimeException("Failed to install exopackage on " + device, e);
            }
          }

          @Override
          public String toString() {
            return "install exopackage";
          }
        });

    eventBus.post(InstallEvent.finished(apkRule.getBuildTarget(), success));
    return success;
  }

  /**
   * Helper class to manage the state required to install on a single device.
   */
  private class SingleDeviceInstaller {

    /**
     * Device that we are installing onto.
     */
    private final IDevice device;

    /**
     * Port to use for sending files to the agent.
     */
    private final int agentPort;

    /**
     * True iff we should use the native agent.
     */
    private boolean useNativeAgent = true;

    /**
     * Set after the agent is installed.
     */
    @Nullable
    private String nativeAgentPath;

    private SingleDeviceInstaller(IDevice device, int agentPort) {
      this.device = device;
      this.agentPort = agentPort;
    }

    boolean doInstall() throws Exception {
      Optional<PackageInfo> agentInfo = installAgentIfNecessary();
      if (!agentInfo.isPresent()) {
        return false;
      }

      nativeAgentPath = agentInfo.get().nativeLibPath;
      determineBestAgent();

      final File apk = apkRule.getApkPath().toFile();
      // TODO(user): Support SD installation.
      final boolean installViaSd = false;

      if (shouldAppBeInstalled()) {
        try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "install_exo_apk")) {
          boolean success = adbHelper.installApkOnDevice(device, apk, installViaSd);
          if (!success) {
            return false;
          }
        }
      }

      if (exopackageInfo.getDexInfo().isPresent()) {
        installSecondaryDexFiles();
      }

      if (exopackageInfo.getNativeLibsInfo().isPresent()) {
        installNativeLibraryFiles();
      }

      // TODO(user): Make this work on Gingerbread.
      try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "kill_app")) {
        AdbHelper.executeCommandWithErrorChecking(device, "am force-stop " + packageName);
      }

      return true;
    }

    private void installSecondaryDexFiles() throws Exception {
      final ImmutableMap<String, Path> hashToSources = getRequiredDexFiles();
      final ImmutableSet<String> requiredHashes = hashToSources.keySet();
      final ImmutableSet<String> presentHashes = prepareSecondaryDexDir(requiredHashes);
      final Set<String> hashesToInstall = Sets.difference(requiredHashes, presentHashes);

      Map<String, Path> filesToInstallByHash =
          Maps.filterKeys(hashToSources, Predicates.in(hashesToInstall));

      // This is a bit gross.  It was a late addition.  Ideally, we could eliminate this, but
      // it wouldn't be terrible if we don't.  We store the dexed jars on the device
      // with the full SHA-1 hashes in their names.  This is the format that the loader uses
      // internally, so ideally we would just load them in place.  However, the code currently
      // expects to be able to copy the jars from a directory that matches the name in the
      // metadata file, like "secondary-1.dex.jar".  We don't want to give up putting the
      // hashes in the file names (because we use that to skip re-uploads), so just hack
      // the metadata file to have hash-like names.
      String metadataContents = com.google.common.io.Files.toString(
          exopackageInfo.getDexInfo().get().getMetadata().toFile(),
          Charsets.UTF_8)
          .replaceAll(
              "secondary-(\\d+)\\.dex\\.jar (\\p{XDigit}{40}) ",
              "secondary-$2.dex.jar $2 ");

      installFiles(
          "secondary_dex",
          ImmutableMap.copyOf(filesToInstallByHash),
          metadataContents,
          "secondary-%s.dex.jar",
          SECONDARY_DEX_DIR);
    }

    private void installNativeLibraryFiles() throws Exception {
      String abi1 = getProperty("ro.product.cpu.abi");
      if (abi1.isEmpty()) {
        throw new RuntimeException("adb returned empty result for ro.product.cpu.abi property.");
      }

      ImmutableMultimap<String, Path> allLibraries = getAllLibraries();

      ImmutableMap<String, Path> abi1Libraries =
          getRequiredLibrariesForAbi(allLibraries, abi1, ImmutableSet.<String>of());
      installNativeLibrariesForAbi(abi1, abi1Libraries);

      String abi2 = getProperty("ro.product.cpu.abi2");
      if (abi2.isEmpty()) {
        return;
      }

      ImmutableSet<String> abi1LibraryNames = FluentIterable.from(abi1Libraries.values())
          .transform(
              new Function<Path, String>() {
                @Override
                public String apply(Path input) {
                  return input.getFileName().toString();
                }
              })
          .toSet();
      ImmutableMap<String, Path> abi2Libraries =
          getRequiredLibrariesForAbi(allLibraries, abi2, abi1LibraryNames);
      installNativeLibrariesForAbi(abi2, abi2Libraries);
    }

    private void installNativeLibrariesForAbi(String abi, ImmutableMap<String, Path> libraries)
        throws Exception {
      if (libraries.isEmpty()) {
        return;
      }

      ImmutableSet<String> requiredHashes = libraries.keySet();
      ImmutableSet<String> presentHashes = prepareNativeLibsDir(abi, requiredHashes);

      Map<String, Path> filesToInstallByHash =
          Maps.filterKeys(libraries, Predicates.not(Predicates.in(presentHashes)));

      if (useNativeAgent) {
        // "ln -s" only works on pre-L Android devices.
        createSymlinks(abi, libraries);
      }

      String metadataContents = Joiner.on('\n').join(
          FluentIterable.from(libraries.entrySet()).transform(
              new Function<Map.Entry<String, Path>, String>() {
                @Override
                public String apply(Map.Entry<String, Path> input) {
                  String hash = input.getKey();
                  String filename = input.getValue().getFileName().toString();
                  int index = filename.indexOf('.');
                  String libname = index == -1 ? filename : filename.substring(0, index);
                  return String.format("%s native-%s.so", libname, hash);
                }
              }));

      installFiles(
          "native_library",
          ImmutableMap.copyOf(filesToInstallByHash),
          metadataContents,
          "native-%s.so",
          NATIVE_LIBS_DIR.resolve(abi));
    }

    /**
     * Create symlinks of the form "lib<name>.so" to the native libraries. We want to do this while
     * minimizing the number of adb shell commands, hence creating chunks of source/target pairs.
     */
    private void createSymlinks(String abi, ImmutableMap<String, Path> libraries) throws Exception {
      try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "create_symlinks_native")) {
        int maxSize = MAX_ADB_COMMAND_SIZE - AdbHelper.ECHO_COMMAND_SUFFIX.length();
        Path abiDir = dataRoot.resolve(NATIVE_LIBS_DIR).resolve(abi);
        String commandPrefix = String.format("cd %s", abiDir.toString());

        String command = commandPrefix;
        for (Map.Entry<String, Path> entry : libraries.entrySet()) {
          String target = entry.getValue().getFileName().toString();
          String source = String.format("native-%s.so", entry.getKey());
          String nextToken = String.format(" && ln -s %s %s", source, target);

          if (command.length() + nextToken.length() > maxSize) {
            LOG.debug("Executing symlink command: " + command);
            AdbHelper.executeCommandWithErrorChecking(device, command);
            command = commandPrefix + nextToken;
          } else {
            command += nextToken;
          }
        }

        if (!command.equals(commandPrefix)) {
          AdbHelper.executeCommandWithErrorChecking(device, command);
        }
      }
    }

    /**
     * Sets {@link #useNativeAgent} to true on pre-L devices, because our native agent is built
     * without -fPIC.  The java agent works fine on L as long as we don't use it for mkdir.
     */
    private void determineBestAgent() throws Exception {
      String value = getProperty("ro.build.version.sdk");
      try {
        if (Integer.valueOf(value.trim()) > 19) {
          useNativeAgent = false;
        }
      } catch (NumberFormatException exn) {
        useNativeAgent = false;
      }
    }

    private String getAgentCommand() {
      if (useNativeAgent) {
        return nativeAgentPath + "/libagent.so ";
      } else {
        return JAVA_AGENT_COMMAND;
      }
    }

    private Optional<PackageInfo> getPackageInfo(final String packageName) throws Exception {
      try (TraceEventLogger ignored = TraceEventLogger.start(
          eventBus,
          "get_package_info",
          ImmutableMap.of("package", packageName))) {

        /* This produces output that looks like

          Package [com.facebook.katana] (4229ce68):
            userId=10145 gids=[1028, 1015, 3003]
            pkg=Package{42690b80 com.facebook.katana}
            codePath=/data/app/com.facebook.katana-1.apk
            resourcePath=/data/app/com.facebook.katana-1.apk
            nativeLibraryPath=/data/app-lib/com.facebook.katana-1
            versionCode=1640376 targetSdk=14
            versionName=8.0.0.0.23

            ...

         */
        String lines = AdbHelper.executeCommandWithErrorChecking(
            device, "dumpsys package " + packageName);

        return parsePackageInfo(packageName, lines);
      }
    }

    /**
     * @return PackageInfo for the agent, or absent if installation failed.
     */
    private Optional<PackageInfo> installAgentIfNecessary() throws Exception {
      Optional<PackageInfo> agentInfo = getPackageInfo(AgentUtil.AGENT_PACKAGE_NAME);
      if (!agentInfo.isPresent()) {
        LOG.debug("Agent not installed.  Installing.");
        return installAgentApk();
      }
      LOG.debug("Agent version: %s", agentInfo.get().versionCode);
      if (!agentInfo.get().versionCode.equals(AgentUtil.AGENT_VERSION_CODE)) {
        // Always uninstall before installing.  We might be downgrading, which requires
        // an uninstall, or we might just want a clean installation.
        uninstallAgent();
        return installAgentApk();
      }
      return agentInfo;
    }

    private void uninstallAgent() throws InstallException {
      try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "uninstall_old_agent")) {
        device.uninstallPackage(AgentUtil.AGENT_PACKAGE_NAME);
      }
    }

    private Optional<PackageInfo> installAgentApk() throws Exception {
      try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "install_agent_apk")) {
        String apkFileName = System.getProperty("buck.android_agent_path");
        if (apkFileName == null) {
          throw new RuntimeException("Android agent apk path not specified in properties");
        }
        File apkPath = new File(apkFileName);
        boolean success = adbHelper.installApkOnDevice(device, apkPath, /* installViaSd */ false);
        if (!success) {
          return Optional.absent();
        }
        return getPackageInfo(AgentUtil.AGENT_PACKAGE_NAME);
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
      String localAppSignature = AgentUtil.getJarSignature(apkRule.getApkPath().toString());
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
      try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "get_app_signature")) {
        String command = getAgentCommand() + "get-signature " + packagePath;
        LOG.debug("Executing %s", command);
        String output = AdbHelper.executeCommandWithErrorChecking(device, command);

        String result = output.trim();
        if (result.contains("\n") || result.contains("\r")) {
          throw new IllegalStateException("Unexpected return from get-signature:\n" + output);
        }

        return result;
      }
    }

    private ImmutableMap<String, Path> getRequiredDexFiles() throws IOException {
      ExopackageInfo.DexInfo dexInfo = exopackageInfo.getDexInfo().get();
      ImmutableMultimap<String, Path> multimap = parseExopackageInfoMetadata(
          dexInfo.getMetadata(),
          dexInfo.getDirectory(),
          projectFilesystem);
      // Convert multimap to a map, because every key should have only one value.
      ImmutableMap.Builder<String, Path> builder = ImmutableMap.builder();
      for (Map.Entry<String, Path> entry : multimap.entries()) {
        builder.put(entry);
      }
      return builder.build();
    }

    private ImmutableSet<String> prepareSecondaryDexDir(ImmutableSet<String> requiredHashes)
        throws Exception {
      return prepareDirectory("secondary-dex", DEX_FILE_PATTERN, requiredHashes);
    }

    private ImmutableSet<String> prepareNativeLibsDir(
        String abi,
        ImmutableSet<String> requiredHashes) throws Exception {
      return prepareDirectory("native-libs/" + abi, NATIVE_LIB_PATTERN, requiredHashes);
    }

    private ImmutableSet<String> prepareDirectory(
        String dirname,
        Pattern filePattern,
        ImmutableSet<String> requiredHashes) throws Exception {
      try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "prepare_" + dirname)) {
        String dirPath = dataRoot.resolve(dirname).toString();
        mkDirP(dirPath);

        String output = AdbHelper.executeCommandWithErrorChecking(device, "ls " + dirPath);

        ImmutableSet.Builder<String> foundHashes = ImmutableSet.builder();
        ImmutableSet.Builder<String> filesToDelete = ImmutableSet.builder();

        processLsOutput(output, filePattern, requiredHashes, foundHashes, filesToDelete);

        String commandPrefix = "cd " + dirPath + " && rm ";
        // Add a fudge factor for separators and error checking.
        final int overhead = commandPrefix.length() + 100;
        for (List<String> rmArgs :
            chunkArgs(filesToDelete.build(), MAX_ADB_COMMAND_SIZE - overhead)) {
          String command = commandPrefix + Joiner.on(' ').join(rmArgs);
          LOG.debug("Executing %s", command);
          AdbHelper.executeCommandWithErrorChecking(device, command);
        }

        return foundHashes.build();
      }
    }

    private void installFiles(
        String filesType,
        ImmutableMap<String, Path> filesToInstallByHash,
        String metadataFileContents,
        String filenameFormat,
        Path destinationDirRelativeToDataRoot) throws Exception {
      try (TraceEventLogger ignored1 =
               TraceEventLogger.start(eventBus, "multi_install_" + filesType)) {
        device.createForward(agentPort, agentPort);
        try {
          for (Map.Entry<String, Path> entry : filesToInstallByHash.entrySet()) {
            Path destination = destinationDirRelativeToDataRoot.resolve(
                String.format(filenameFormat, entry.getKey()));
            Path source = entry.getValue();

            try (TraceEventLogger ignored2 =
                     TraceEventLogger.start(eventBus, "install_" + filesType)) {
              installFile(device, agentPort, destination, source);
            }
          }
          try (TraceEventLogger ignored3 =
                   TraceEventLogger.start(eventBus, "install_" + filesType + "_metadata")) {
            try (NamedTemporaryFile temp = new NamedTemporaryFile("metadata", "tmp")) {
              com.google.common.io.Files.write(
                  metadataFileContents.getBytes(Charsets.UTF_8),
                  temp.get().toFile());
              installFile(
                  device,
                  agentPort,
                  destinationDirRelativeToDataRoot.resolve("metadata.txt"),
                  temp.get());
            }
          }
        } finally {
          try {
            device.removeForward(agentPort, agentPort);
          } catch (AdbCommandRejectedException e) {
            LOG.warn(e, "Failed to remove adb forward on port %d for device %s", agentPort, device);
            eventBus.post(
                ConsoleEvent.warning(
                    "Failed to remove adb forward %d. This is not necessarily a problem\n" +
                        "because it will be recreated during the next exopackage installation.\n" +
                        "See the log for the full exception.",
                    agentPort));
          }
        }
      }
    }

    private void installFile(
        IDevice device,
        final int port,
        Path pathRelativeToDataRoot,
        final Path source) throws Exception {
      CollectingOutputReceiver receiver = new CollectingOutputReceiver() {

        private boolean sentPayload = false;

        @Override
        public void addOutput(byte[] data, int offset, int length) {
          super.addOutput(data, offset, length);
          if (!sentPayload && getOutput().length() >= AgentUtil.TEXT_SECRET_KEY_SIZE) {
            LOG.verbose("Got key: %s", getOutput().trim());

            sentPayload = true;
            try (Socket clientSocket = new Socket("localhost", port)) {
              LOG.verbose("Connected");
              OutputStream outToDevice = clientSocket.getOutputStream();
              outToDevice.write(
                  getOutput().substring(
                      0,
                      AgentUtil.TEXT_SECRET_KEY_SIZE).getBytes());
              LOG.verbose("Wrote key");
              com.google.common.io.Files.asByteSource(source.toFile()).copyTo(outToDevice);
              LOG.verbose("Wrote file");
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        }
      };

      String targetFileName = dataRoot.resolve(pathRelativeToDataRoot).toString();
      String command =
          "umask 022 && " +
              getAgentCommand() +
              "receive-file " + port + " " + Files.size(source) + " " +
              targetFileName +
              " ; echo -n :$?";
      LOG.debug("Executing %s", command);

      // If we fail to execute the command, stash the exception.  My experience during development
      // has been that the exception from checkReceiverOutput is more actionable.
      Exception shellException = null;
      try {
        device.executeShellCommand(command, receiver);
      } catch (Exception e) {
        shellException = e;
      }

      try {
        AdbHelper.checkReceiverOutput(command, receiver);
      } catch (Exception e) {
        if (shellException != null) {
          e.addSuppressed(shellException);
        }
        throw e;
      }

      if (shellException != null) {
        throw shellException;
      }

      // The standard Java libraries on Android always create new files un-readable by other users.
      // We use the shell user or root to create these files, so we need to explicitly set the mode
      // to allow the app to read them.  Ideally, the agent would do this automatically, but
      // there's no easy way to do this in Java.  We can drop this if we drop support for the
      // Java agent.
      AdbHelper.executeCommandWithErrorChecking(device, "chmod 644 " + targetFileName);
    }

    private String getProperty(String property) throws Exception {
      return AdbHelper.executeCommandWithErrorChecking(device, "getprop " + property).trim();
    }

    private void mkDirP(String dirpath) throws Exception {
      // Kind of a hack here.  The java agent can't force the proper permissions on the
      // directories it creates, so we use the command-line "mkdir -p" instead of the java agent.
      // Fortunately, "mkdir -p" seems to work on all devices where we use use the java agent.
      String mkdirP = useNativeAgent ? getAgentCommand() + "mkdir-p" : "mkdir -p";

      AdbHelper.executeCommandWithErrorChecking(device, "umask 022 && " + mkdirP + " " + dirpath);
    }
  }

  private ImmutableMultimap<String, Path> getAllLibraries() throws IOException {
    ExopackageInfo.NativeLibsInfo nativeLibsInfo = exopackageInfo.getNativeLibsInfo().get();
    return parseExopackageInfoMetadata(
        nativeLibsInfo.getMetadata(),
        nativeLibsInfo.getDirectory(),
        projectFilesystem);
  }

  private ImmutableMap<String, Path> getRequiredLibrariesForAbi(
      ImmutableMultimap<String, Path> allLibraries,
      String abi,
      ImmutableSet<String> ignoreLibraries) throws IOException {
    return filterLibrariesForAbi(
        exopackageInfo.getNativeLibsInfo().get().getDirectory(),
        allLibraries,
        abi,
        ignoreLibraries);
  }

  @VisibleForTesting
  static ImmutableMap<String, Path> filterLibrariesForAbi(
      Path nativeLibsDir,
      ImmutableMultimap<String, Path> allLibraries,
      String abi,
      ImmutableSet<String> ignoreLibraries) {
    ImmutableMap.Builder<String, Path> filteredLibraries = ImmutableMap.builder();
    for (Map.Entry<String, Path> entry : allLibraries.entries()) {
      Path relativePath = nativeLibsDir.relativize(entry.getValue());
      Preconditions.checkState(relativePath.getNameCount() == 2);
      String libAbi = relativePath.getParent().toString();
      String libName = relativePath.getFileName().toString();
      if (libAbi.equals(abi) && !ignoreLibraries.contains(libName)) {
        filteredLibraries.put(entry);
      }
    }
    return filteredLibraries.build();
  }

  /**
   * Parses a text file which is supposed to be in the following format:
   * "file_path_without_spaces file_hash ...." i.e. it parses the first two columns of each line
   * and ignores the rest of it.
   *
   * @return  A multi map from the file hash to its path, which equals the raw path resolved against
   *     {@code resolvePathAgainst}.
   */
  @VisibleForTesting
  static ImmutableMultimap<String, Path> parseExopackageInfoMetadata(
      Path metadataTxt,
      Path resolvePathAgainst,
      ProjectFilesystem filesystem) throws IOException {
    ImmutableMultimap.Builder<String, Path> builder = ImmutableMultimap.builder();
    for (String line : filesystem.readLines(metadataTxt)) {
      List<String> parts = Splitter.on(' ').splitToList(line);
      if (parts.size() < 2) {
        throw new RuntimeException("Illegal line in metadata file: " + line);
      }
      builder.put(parts.get(1), resolvePathAgainst.resolve(parts.get(0)));
    }
    return builder.build();
  }

  @VisibleForTesting
  static Optional<PackageInfo> parsePackageInfo(String packageName, String lines) {
    final String packagePrefix = "  Package [" + packageName + "] (";
    final String otherPrefix = "  Package [";
    boolean sawPackageLine = false;
    final Splitter splitter = Splitter.on('=').limit(2);

    String codePath = null;
    String resourcePath = null;
    String nativeLibPath = null;
    String versionCode = null;

    for (String line : Splitter.on("\r\n").split(lines)) {
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
      return Optional.absent();
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
   * @param output  Output of "ls" command.
   * @param filePattern  A {@link Pattern} that is used to check if a file is valid, and if it
   *     matches, {@code filePattern.group(1)} should return the hash in the file name.
   * @param requiredHashes  Hashes of dex files required for this apk.
   * @param foundHashes  Builder to receive hashes that we need and were found.
   * @param toDelete  Builder to receive files that we need to delete.
   */
  @VisibleForTesting
  static void processLsOutput(
      String output,
      Pattern filePattern,
      ImmutableSet<String> requiredHashes,
      ImmutableSet.Builder<String> foundHashes,
      ImmutableSet.Builder<String> toDelete) {
    for (String line : Splitter.on("\r\n").omitEmptyStrings().split(output)) {
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

  /**
   * Breaks a list of strings into groups whose total size is within some limit.
   * Kind of like the xargs command that groups arguments to avoid maximum argument length limits.
   * Except that the limit in adb is about 1k instead of 512k or 2M on Linux.
   */
  @VisibleForTesting
  static ImmutableList<ImmutableList<String>> chunkArgs(Iterable<String> args, int sizeLimit) {
    ImmutableList.Builder<ImmutableList<String>> topLevelBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> chunkBuilder = ImmutableList.builder();
    int chunkSize = 0;
    for (String arg : args) {
      if (chunkSize + arg.length() > sizeLimit) {
        topLevelBuilder.add(chunkBuilder.build());
        chunkBuilder = ImmutableList.builder();
        chunkSize = 0;
      }
      // We don't check for an individual arg greater than the limit.
      // We just put it in its own chunk and hope for the best.
      chunkBuilder.add(arg);
      chunkSize += arg.length();
    }
    ImmutableList<String> tail = chunkBuilder.build();
    if (!tail.isEmpty()) {
      topLevelBuilder.add(tail);
    }
    return topLevelBuilder.build();
  }
}
