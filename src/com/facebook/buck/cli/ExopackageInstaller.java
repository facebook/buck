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

import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.IDevice;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.event.TraceEventLogger;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.NamedTemporaryFile;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * ExopackageInstaller manages the installation of apps with the "exopackage" flag set to true.
 */
public class ExopackageInstaller {

  /**
   * Pattern that matches safe package names.  (Must be a full string match).
   */
  private static final Pattern PACKAGE_NAME_PATTERN = Pattern.compile("[\\w.-]+");

  /**
   * Prefix of the path to the agent apk on the device.
   */
  private static final String AGENT_DEVICE_PATH = "/data/app/" + AgentUtil.AGENT_PACKAGE_NAME;

  /**
   * Command line to invoke the agent on the device.
   */
  private static final String JAVA_AGENT_COMMAND =
      "dalvikvm -classpath " +
      AGENT_DEVICE_PATH + "-1.apk:" + AGENT_DEVICE_PATH + "-2.apk " +
      "com.facebook.buck.android.agent.AgentMain ";

  /**
   * Port to use for sending dex files.
   */
  private static final int AGENT_PORT = 2828;

  /**
   * Maximum length of commands that can be passed to "adb shell".
   */
  private static final int MAX_ADB_COMMAND_SIZE = 1019;

  private static final boolean USE_NATIVE_AGENT = true;

  private final ProjectFilesystem projectFilesystem;
  private final BuckEventBus eventBus;
  private final AdbHelper adbHelper;
  private final InstallableApk apkRule;
  private final String packageName;
  private final InstallableApk.ExopackageInfo exopackageInfo;

  /**
   * Set in {@link #install}.
   */
  private IDevice device = null;

  /**
   * Set after the agent is installed.
   */
  private String nativeAgentPath;

  @VisibleForTesting
  static class PackageInfo {
    final String apkPath;
    final String nativeLibPath;
    final String versionCode;
    private PackageInfo(String apkPath, String nativeLibPath, String versionCode) {
      this.nativeLibPath = Preconditions.checkNotNull(nativeLibPath);
      this.apkPath = Preconditions.checkNotNull(apkPath);
      this.versionCode = Preconditions.checkNotNull(versionCode);
    }
  }

  public ExopackageInstaller(
      ExecutionContext context,
      AdbHelper adbHelper,
      InstallableApk apkRule) {
    this.adbHelper = Preconditions.checkNotNull(adbHelper);
    this.projectFilesystem = context.getProjectFilesystem();
    this.eventBus = context.getBuckEventBus();
    this.apkRule = Preconditions.checkNotNull(apkRule);
    this.packageName = AdbHelper.tryToExtractPackageNameFromManifest(apkRule);

    Preconditions.checkArgument(PACKAGE_NAME_PATTERN.matcher(packageName).matches());

    Optional<InstallableApk.ExopackageInfo> exopackageInfo = apkRule.getExopackageInfo();
    Preconditions.checkArgument(exopackageInfo.isPresent());
    this.exopackageInfo = exopackageInfo.get();
  }

  /**
   * Installs the app specified in the constructor.  This object should be discarded afterward.
   */
  public synchronized boolean install() {
    Preconditions.checkState(
        device == null,
        "ExopackageInstaller.install called twice.");

    eventBus.post(InstallEvent.started(apkRule.getBuildTarget()));

    boolean success = adbHelper.adbCall(
        new AdbHelper.AdbCallable() {
          @Override
          public boolean call(IDevice device) throws Exception {
            ExopackageInstaller.this.device = device;
            return doInstall();
          }

          @Override
          public String toString() {
            return "install exopackage";
          }
        });

    eventBus.post(InstallEvent.finished(apkRule.getBuildTarget(), success));
    return success;
  }

  private boolean doInstall() throws Exception {
    Optional<PackageInfo> agentInfo = installAgentIfNecessary();
    if (!agentInfo.isPresent()) {
      return false;
    }

    nativeAgentPath = agentInfo.get().nativeLibPath;

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

    final ImmutableMap<String, String> hashToBasename = getRequiredDexFiles();
    final ImmutableSet<String> requiredHashes = hashToBasename.keySet();
    final ImmutableSet<String> presentHashes = prepareSecondaryDexDir(requiredHashes);
    final Set<String> hashesToInstall = Sets.difference(requiredHashes, presentHashes);

    installSecondaryDexFiles(hashesToInstall, hashToBasename);

    // TODO(user): Make this work on Gingerbread.
    AdbHelper.executeCommandWithErrorChecking(device, "am force-stop " + packageName);

    return true;
  }

  private String getAgentCommand() {
    if (USE_NATIVE_AGENT) {
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

    return Optional.of(new PackageInfo(codePath, nativeLibPath, versionCode));
  }

  /**
   * @return  PackageInfo for the agent, or absent if installation failed.
   */
  private Optional<PackageInfo> installAgentIfNecessary() throws Exception {
    Optional<PackageInfo> agentInfo = getPackageInfo(AgentUtil.AGENT_PACKAGE_NAME);
    if (!agentInfo.isPresent()) {
      logFine("Agent not installed.  Installing.");
      return installAgentApk();
    }
    logFine("Agent version: %s", agentInfo.get().versionCode);
    if (!agentInfo.get().versionCode.equals(AgentUtil.AGENT_VERSION_CODE)) {
      return installAgentApk();
    }
    return agentInfo;
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
      eventBus.post(LogEvent.info("App not installed.  Installing now."));
      return true;
    }

    logFine("App path: %s", appPackageInfo.get().apkPath);
    String installedAppSignature = getInstalledAppSignature(appPackageInfo.get().apkPath);
    String localAppSignature = AgentUtil.getJarSignature(apkRule.getApkPath().toString());
    logFine("Local app signature: %s", localAppSignature);
    logFine("Remote app signature: %s", installedAppSignature);

    if (!installedAppSignature.equals(localAppSignature)) {
      logFine("App signatures do not match.  Must re-install.");
      return true;
    }

    logFine("App signatures match.  No need to install.");
    return false;
  }

  private String getInstalledAppSignature(final String packagePath) throws Exception {
    try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "get_app_signature")) {
      String command = getAgentCommand() + "get-signature " + packagePath;
      logFine("Executing %s", command);
      String output = AdbHelper.executeCommandWithErrorChecking(device, command);

      String result = output.trim();
      if (result.contains("\n") || result.contains("\r")) {
        throw new IllegalStateException("Unexpected return from get-signature:\n" + output);
      }

      return result;
    }
  }

  private ImmutableMap<String, String> getRequiredDexFiles() throws IOException {
    ImmutableMap.Builder<String, String> hashToBasenameBuilder = ImmutableMap.builder();
    for (String line : projectFilesystem.readLines(exopackageInfo.metadata)) {
      List<String> parts = Splitter.on(' ').splitToList(line);
      if (parts.size() < 2) {
        throw new RuntimeException("Illegal line in metadata file: " + line);
      }
      hashToBasenameBuilder.put(parts.get(1), parts.get(0));
    }
    return hashToBasenameBuilder.build();
  }

  private ImmutableSet<String> prepareSecondaryDexDir(ImmutableSet<String> requiredHashes)
      throws Exception {
    try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "prepare_dex_dir")) {
      final ImmutableSet.Builder<String> foundHashes = ImmutableSet.builder();

      AdbHelper.executeCommandWithErrorChecking(
          device, "run-as " + packageName + " mkdir -p app_exopackage/secondary-dex");
      String output = AdbHelper.executeCommandWithErrorChecking(
          device, "run-as " + packageName + " ls app_exopackage/secondary-dex");

      ImmutableSet.Builder<String> toDeleteBuilder = ImmutableSet.builder();

      scanSecondaryDexDir(output, requiredHashes, foundHashes, toDeleteBuilder);

      ImmutableList<String> filesToDelete = FluentIterable.from(toDeleteBuilder.build())
          .transform(new Function<String, String>() {
            @Override
            public String apply(@Nullable String input) {
              return "app_exopackage/secondary-dex/" + input;
            }
          }).toList();

      String commandPrefix = "run-as " + packageName + " rm ";
      // Add a fudge factor for separators and error checking.
      final int overhead = commandPrefix.length() + 100;
      for (List<String> rmArgs : chunkArgs(filesToDelete, MAX_ADB_COMMAND_SIZE - overhead)) {
        String command = commandPrefix + Joiner.on(' ').join(rmArgs);
        logFine("Running: %s", command);
        AdbHelper.executeCommandWithErrorChecking(device, command);
      }

      return foundHashes.build();
    }
  }

  /**
   * @param output  Output of "ls" command.
   * @param requiredHashes  Hashes of dex files required for this apk.
   * @param foundHashesBuilder  Builder to receive hashes that we need and were found.
   * @param toDeleteBuilder  Builder to receive files that we need to delete.
   */
  @VisibleForTesting
  static void scanSecondaryDexDir(
      String output,
      ImmutableSet<String> requiredHashes,
      ImmutableSet.Builder<String> foundHashesBuilder,
      ImmutableSet.Builder<String> toDeleteBuilder) {
    Pattern dexFilePattern = Pattern.compile("secondary-([0-9a-f]+)\\.[\\w.-]*");

    for (String line : Splitter.on("\r\n").split(output)) {
      if (line.equals("metadata.txt") || line.startsWith(AgentUtil.TEMP_PREFIX)) {
        toDeleteBuilder.add(line);
        continue;
      }

      Matcher m = dexFilePattern.matcher(line);
      if (m.matches()) {
        if (requiredHashes.contains(m.group(1))) {
          foundHashesBuilder.add(m.group(1));
        } else {
          toDeleteBuilder.add(line);
        }
      }
    }
  }

  private void installSecondaryDexFiles(
      Set<String> hashesToInstall,
      ImmutableMap<String, String> hashToBasename)
      throws Exception {
    try (TraceEventLogger ignored1 = TraceEventLogger.start(eventBus, "install_secondary_dexes")) {
      device.createForward(AGENT_PORT, AGENT_PORT);
      try {
        for (String hash : hashesToInstall) {
          String basename = hashToBasename.get(hash);
          try (TraceEventLogger ignored2 = TraceEventLogger.start(
              eventBus,
              "install_secondary_dex",
              ImmutableMap.of("basename", basename))) {
            installSecondaryDex(
              device,
              AGENT_PORT,
              hash,
              exopackageInfo.dexDirectory.resolve(basename));
          }
        }
        try (TraceEventLogger ignored2 = TraceEventLogger.start(
            eventBus,
            "install_secondary_dex_metadata")) {

          // This is a bit gross.  It was a late addition.  Ideally, we could eliminate this, but
          // it wouldn't be terrible if we don't.  We store the dexed jars on the device
          // with the full SHA-1 hashes in their names.  This is the format that the loader uses
          // internally, so ideally we would just load them in place.  However, the code currently
          // expects to be able to copy the jars from a directory that matches the name in the
          // metadata file, like "secondary-1.dex.jar".  We don't want to give up putting the
          // hashes in the file names (because we use that to skip re-uploads), so just hack
          // the metadata file to have hash-like names.
          try (NamedTemporaryFile temp = new NamedTemporaryFile("metadata", "tmp")) {
            com.google.common.io.Files.write(
                com.google.common.io.Files.toString(
                    exopackageInfo.metadata.toFile(),
                    Charsets.UTF_8)
                    .replaceAll(
                      "secondary-(\\d+)\\.dex\\.jar (\\p{XDigit}{40}) ",
                      "secondary-$2.dex.jar $2 "),
                temp.get().toFile(), Charsets.UTF_8);

            installFile(
                device,
                AGENT_PORT,
                "metadata.txt",
                temp.get());
          }
        }
      } finally {
        device.removeForward(AGENT_PORT, AGENT_PORT);
      }
    }
  }

  private void installSecondaryDex(
      final IDevice device,
      final int port,
      String hash,
      final Path source)
      throws Exception {
    installFile(device, port, "secondary-" + hash + ".dex.jar", source);
  }

  private void installFile(
      IDevice device,
      final int port,
      String basename,
      final Path source) throws Exception {
    CollectingOutputReceiver receiver = new CollectingOutputReceiver() {

      private boolean sentPayload = false;

      @Override
      public void addOutput(byte[] data, int offset, int length) {
        super.addOutput(data, offset, length);
        if (!sentPayload && getOutput().length() >= AgentUtil.TEXT_SECRET_KEY_SIZE) {
          logFiner("Got key: %s", getOutput());

          sentPayload = true;
          try (Socket clientSocket = new Socket("localhost", port)) {
            logFiner("Connected");
            OutputStream outToDevice = clientSocket.getOutputStream();
            outToDevice.write(
                getOutput().substring(
                    0,
                    AgentUtil.TEXT_SECRET_KEY_SIZE).getBytes());
            logFiner("Wrote key");
            com.google.common.io.Files.asByteSource(source.toFile()).copyTo(outToDevice);
            logFiner("Wrote file");
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      }
    };

    // In some emulators, running the agent under run-as caused an EACCES during
    // the socket operation.
    String runAsPrefix;
    String dataDirPrefix;
    if (!device.isEmulator()) {
      runAsPrefix = "run-as " + packageName + " ";
      dataDirPrefix = "";
    } else {
      runAsPrefix = "";
      dataDirPrefix = "/data/data/" + packageName + "/";
    }
    String targetFileName = dataDirPrefix + "app_exopackage/secondary-dex/" + basename;
    String command =
        runAsPrefix + getAgentCommand() +
            "receive-file " + port + " " + Files.size(source) + " " +
            targetFileName +
            " ; echo -n :$?";
    logFine("Executing %s", command);

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

    if (device.isEmulator()) {
      // The standard Java libraries on Android always create new files un-readable by other users.
      // In the emulator, we use root to create these files, so we need to explicitly set the mode
      // to allow the app to read them.  Ideally, the agent would do this automatically, but
      // there's no easy way to do this in Java.
      AdbHelper.executeCommandWithErrorChecking(device, "chmod 644 " + targetFileName);
    }
  }

  private void logFine(String message, Object... args) {
    eventBus.post(LogEvent.fine(message, args));
  }

  private void logFiner(String message, Object... args) {
    eventBus.post(LogEvent.finer(message, args));
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
