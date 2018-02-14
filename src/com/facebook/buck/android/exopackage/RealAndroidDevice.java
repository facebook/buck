/*
 * Copyright 2017-present Facebook, Inc.
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

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.MoreSuppliers;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;
import com.google.common.primitives.Ints;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@VisibleForTesting
public class RealAndroidDevice implements AndroidDevice {
  private static final Logger LOG = Logger.get(RealAndroidDevice.class);

  private static final String ECHO_COMMAND_SUFFIX = " ; echo -n :$?";
  // Taken from ddms source code.
  private static final long INSTALL_TIMEOUT = 2 * 60 * 1000; // 2 min
  private static final long GETPROP_TIMEOUT = 2 * 1000; // 2 seconds

  /** Pattern that matches Genymotion serial numbers. */
  private static final Pattern RE_LOCAL_TRANSPORT_SERIAL =
      Pattern.compile("\\d+\\.\\d+\\.\\d+\\.\\d+:\\d+");

  /** Maximum length of commands that can be passed to "adb shell". */
  private static final int MAX_ADB_COMMAND_SIZE = 1019;

  private static final Pattern LINE_ENDING = Pattern.compile("\r?\n");

  private final BuckEventBus eventBus;
  private final IDevice device;
  private final Console console;
  private final ImmutableList<String> rapidInstallTypes;
  private final Supplier<ExopackageAgent> agent;
  private final int agentPort;

  public RealAndroidDevice(
      BuckEventBus eventBus,
      IDevice device,
      Console console,
      @Nullable Path agentApkPath,
      int agentPort,
      ImmutableList<String> rapidInstallTypes) {
    this.eventBus = eventBus;
    this.device = device;
    this.console = console;
    this.rapidInstallTypes = rapidInstallTypes;
    this.agent =
        MoreSuppliers.memoize(
            () ->
                ExopackageAgent.installAgentIfNecessary(
                    eventBus,
                    this,
                    Preconditions.checkNotNull(
                        agentApkPath, "Agent not configured for this device.")));
    this.agentPort = agentPort;
  }

  public RealAndroidDevice(BuckEventBus buckEventBus, IDevice device, Console console) {
    this(buckEventBus, device, console, null, -1, ImmutableList.of());
  }

  /**
   * Breaks a list of strings into groups whose total size is within some limit. Kind of like the
   * xargs command that groups arguments to avoid maximum argument length limits. Except that the
   * limit in adb is about 1k instead of 512k or 2M on Linux.
   */
  @VisibleForTesting
  public static ImmutableList<ImmutableList<String>> chunkArgs(
      Iterable<String> args, int sizeLimit) {
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

  private static String checkReceiverOutput(String command, CollectingOutputReceiver receiver)
      throws AdbHelper.CommandFailedException {
    String fullOutput = receiver.getOutput();
    int colon = fullOutput.lastIndexOf(':');
    String realOutput = fullOutput.substring(0, colon);
    String exitCodeStr = fullOutput.substring(colon + 1);
    int exitCode = Integer.parseInt(exitCodeStr);
    if (exitCode != 0) {
      throw new AdbHelper.CommandFailedException(command, exitCode, realOutput);
    }
    return realOutput;
  }

  private String executeCommandWithErrorChecking(String command)
      throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
          IOException {
    CollectingOutputReceiver receiver = new CollectingOutputReceiver();
    device.executeShellCommand(command + ECHO_COMMAND_SUFFIX, receiver);
    return checkReceiverOutput(command, receiver);
  }

  /** Retrieves external storage location (SD card) from device. */
  @Nullable
  private String deviceGetExternalStorage()
      throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
          IOException {
    CollectingOutputReceiver receiver = new CollectingOutputReceiver();
    device.executeShellCommand(
        "echo $EXTERNAL_STORAGE", receiver, GETPROP_TIMEOUT, TimeUnit.MILLISECONDS);
    String value = receiver.getOutput().trim();
    if (value.isEmpty()) {
      return null;
    }
    return value;
  }

  /** Installs apk on device, copying apk to external storage first. */
  @Nullable
  private String deviceInstallPackageViaSd(String apk) {
    try {
      // Figure out where the SD card is mounted.
      String externalStorage = deviceGetExternalStorage();
      if (externalStorage == null) {
        return "Cannot get external storage location.";
      }
      String remotePackage = String.format("%s/%s.apk", externalStorage, UUID.randomUUID());
      // Copy APK to device
      device.pushFile(apk, remotePackage);
      // Install
      device.installRemotePackage(remotePackage, true);
      // Delete temporary file
      device.removeRemotePackage(remotePackage);
      return null;
    } catch (Throwable t) {
      return String.valueOf(t.getMessage());
    }
  }

  @VisibleForTesting
  @Nullable
  public String deviceStartActivity(String activityToRun, boolean waitForDebugger) {
    try {
      ErrorParsingReceiver receiver =
          new ErrorParsingReceiver() {
            @Override
            @Nullable
            protected String matchForError(String line) {
              // Parses output from shell am to determine if activity was started correctly.
              return (Pattern.matches("^([\\w_$.])*(Exception|Error|error).*$", line)
                      || line.contains("am: not found"))
                  ? line
                  : null;
            }
          };
      final String waitForDebuggerFlag = waitForDebugger ? "-D" : "";
      device.executeShellCommand(
          //  0x10200000 is FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | FLAG_ACTIVITY_NEW_TASK; the
          // constant values are public ABI.  This way of invoking "am start" makes buck install -r
          // act just like the launcher, avoiding activity duplication on subsequent
          // launcher starts.
          String.format(
              "am start -f 0x10200000 -a android.intent.action.MAIN "
                  + "-c android.intent.category.LAUNCHER -n %s %s",
              activityToRun, waitForDebuggerFlag),
          receiver,
          INSTALL_TIMEOUT,
          TimeUnit.MILLISECONDS);
      return receiver.getErrorMessage();
    } catch (Exception e) {
      return e.toString();
    }
  }

  /**
   * Modified version of <a href="http://fburl.com/8840769">Device.uninstallPackage()</a>.
   *
   * @param packageName application package name
   * @param keepData true if user data is to be kept
   * @return error message or null if successful
   * @throws InstallException
   */
  @Nullable
  private String deviceUninstallPackage(String packageName, boolean keepData)
      throws InstallException {
    try {
      try {
        executeCommandWithErrorChecking(
            String.format("rm -r %s/%s", ExopackageInstaller.EXOPACKAGE_INSTALL_ROOT, packageName));
      } catch (AdbHelper.CommandFailedException e) {
        LOG.debug("Deleting old files failed with message: %s", e.getMessage());
      }

      ErrorParsingReceiver receiver =
          new ErrorParsingReceiver() {
            @Override
            @Nullable
            protected String matchForError(String line) {
              return line.toLowerCase(Locale.US).contains("failure") ? line : null;
            }
          };
      device.executeShellCommand(
          "pm uninstall " + (keepData ? "-k " : "") + packageName,
          receiver,
          INSTALL_TIMEOUT,
          TimeUnit.MILLISECONDS);
      return receiver.getErrorMessage();
    } catch (AdbCommandRejectedException
        | IOException
        | ShellCommandUnresponsiveException
        | TimeoutException e) {
      throw new InstallException(e);
    }
  }

  /**
   * Uninstalls apk from specific device. Reports success or failure to console. It's currently here
   * because it's used both by {@link com.facebook.buck.cli.InstallCommand} and {@link
   * com.facebook.buck.cli.UninstallCommand}.
   */
  @SuppressWarnings("PMD.PrematureDeclaration")
  public boolean uninstallApkFromDevice(String packageName, boolean keepData) {
    String name;
    if (device.isEmulator()) {
      name = device.getSerialNumber() + " (" + device.getAvdName() + ")";
    } else {
      name = device.getSerialNumber();
      String model = device.getProperty("ro.product.model");
      if (model != null) {
        name += " (" + model + ")";
      }
    }

    PrintStream stdOut = console.getStdOut();
    stdOut.printf("Removing apk from %s.\n", name);
    try {
      long start = System.currentTimeMillis();
      String reason = deviceUninstallPackage(packageName, keepData);
      long end = System.currentTimeMillis();

      if (reason != null) {
        console.printBuildFailure(
            String.format("Failed to uninstall apk from %s: %s.", name, reason));
        return false;
      }

      long delta = end - start;
      stdOut.printf("Uninstalled apk from %s in %d.%03ds.\n", name, delta / 1000, delta % 1000);
      return true;

    } catch (InstallException ex) {
      console.printBuildFailure(String.format("Failed to uninstall apk from %s.", name));
      ex.printStackTrace(console.getStdErr());
      return false;
    }
  }

  public boolean isEmulator() {
    return isLocalTransport() || device.isEmulator();
  }

  /**
   * To be consistent with adb, we treat all local transports (as opposed to USB transports) as
   * emulators instead of devices.
   */
  private boolean isLocalTransport() {
    return RE_LOCAL_TRANSPORT_SERIAL.matcher(device.getSerialNumber()).find();
  }

  @VisibleForTesting
  private boolean isDeviceTempWritable(String name) {
    StringBuilder loggingInfo = new StringBuilder();
    try {
      String output;

      try {
        output = executeCommandWithErrorChecking("ls -l -d /data/local/tmp");
        if (!(
        // Pattern for Android's "toolbox" version of ls
        output.matches("\\Adrwx....-x +shell +shell.* tmp[\\r\\n]*\\z")
            ||
            // Pattern for CyanogenMod's busybox version of ls
            output.matches("\\Adrwx....-x +[0-9]+ +shell +shell.* /data/local/tmp[\\r\\n]*\\z"))) {
          loggingInfo.append(
              String.format(Locale.ENGLISH, "Bad ls output for /data/local/tmp: '%s'\n", output));
        }

        executeCommandWithErrorChecking("echo exo > /data/local/tmp/buck-experiment");
        output = executeCommandWithErrorChecking("cat /data/local/tmp/buck-experiment");
        if (!output.matches("\\Aexo[\\r\\n]*\\z")) {
          loggingInfo.append(
              String.format(
                  Locale.ENGLISH, "Bad echo/cat output for /data/local/tmp: '%s'\n", output));
        }
        executeCommandWithErrorChecking("rm /data/local/tmp/buck-experiment");

      } catch (AdbHelper.CommandFailedException e) {
        loggingInfo.append(
            String.format(
                Locale.ENGLISH, "Failed (%d) '%s':\n%s\n", e.exitCode, e.command, e.output));
      }

      if (!loggingInfo.toString().isEmpty()) {
        CollectingOutputReceiver receiver = new CollectingOutputReceiver();
        device.executeShellCommand("getprop", receiver);
        for (String line : Splitter.on('\n').split(receiver.getOutput())) {
          if (line.contains("ro.product.model") || line.contains("ro.build.description")) {
            loggingInfo.append(line).append('\n');
          }
        }
      }

    } catch (AdbCommandRejectedException
        | ShellCommandUnresponsiveException
        | TimeoutException
        | IOException e) {
      console.printBuildFailure(String.format("Failed to test /data/local/tmp on %s.", name));
      e.printStackTrace(console.getStdErr());
      return false;
    }
    String logMessage = loggingInfo.toString();
    if (!logMessage.isEmpty()) {
      StringBuilder fullMessage = new StringBuilder();
      fullMessage.append("============================================================\n");
      fullMessage.append('\n');
      fullMessage.append("HEY! LISTEN!\n");
      fullMessage.append('\n');
      fullMessage.append("The /data/local/tmp directory on your device isn't fully-functional.\n");
      fullMessage.append("Here's some extra info:\n");
      fullMessage.append(logMessage);
      fullMessage.append("============================================================\n");
      console.getStdErr().println(fullMessage.toString());
    }

    return true;
  }

  @Override
  public boolean installApkOnDevice(
      File apk, boolean installViaSd, boolean quiet, boolean verifyTempWritable) {
    String name;
    if (device.isEmulator()) {
      name = device.getSerialNumber() + " (" + device.getAvdName() + ")";
    } else {
      name = device.getSerialNumber();
      String model = device.getProperty("ro.product.model");
      if (model != null) {
        name += " (" + model + ")";
      }
    }

    if (verifyTempWritable && !isDeviceTempWritable(name)) {
      return false;
    }

    if (!quiet) {
      eventBus.post(ConsoleEvent.info("Installing apk on %s.", name));
    }
    try {
      String reason = null;
      if (installViaSd) {
        reason = deviceInstallPackageViaSd(apk.getAbsolutePath());
      } else {
        // Always pass -d if it's supported. The duplication is unfortunate
        // but there's no easy way to conditionally pass varargs in Java.
        if (supportsInstallDowngrade()) {
          device.installPackage(apk.getAbsolutePath(), true, "-d");
        } else {
          device.installPackage(apk.getAbsolutePath(), true);
        }
      }
      if (reason != null) {
        console.printBuildFailure(String.format("Failed to install apk on %s: %s.", name, reason));
        return false;
      }
      return true;
    } catch (InstallException ex) {
      console.printBuildFailure(String.format("Failed to install apk on %s.", name));
      ex.printStackTrace(console.getStdErr());
      return false;
    }
  }

  /**
   * The -d flag is only recognized on API level 17 and above. It was added to android/base in
   * 7767eac3232ba2fb9828766813cdb481d6a97584. The next release was jb-mr1-release,
   * b361b3043bb351ab175ae1ff3dae4de9fe31d42b.
   */
  private boolean supportsInstallDowngrade() {
    String value = device.getProperty("ro.build.version.sdk");
    try {
      if (Integer.valueOf(value) >= 17) {
        return true;
      }
    } catch (NumberFormatException exn) {
      return false;
    }
    return false;
  }

  @Override
  public void stopPackage(String packageName) throws Exception {
    executeCommandWithErrorChecking("am force-stop " + packageName);
  }

  @Override
  public Optional<PackageInfo> getPackageInfo(String packageName) throws Exception {
    /* "dumpsys package <package>" produces output that looks like

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
    // We call "pm path" because "dumpsys package" returns valid output if an app has been
    // uninstalled using the "--keepdata" option. "pm path", on the other hand, returns an empty
    // output in that case.
    String lines =
        executeCommandWithErrorChecking(
            String.format("pm path %s; dumpsys package %s", packageName, packageName));

    return parsePathAndPackageInfo(packageName, lines);
  }

  @Override
  public void uninstallPackage(String packageName) throws InstallException {
    device.uninstallPackage(packageName);
  }

  @Override
  public String getSignature(String packagePath) throws Exception {
    String command = agent.get().getAgentCommand() + "get-signature " + packagePath;
    LOG.debug("Executing %s", command);
    return executeCommandWithErrorChecking(command);
  }

  @Override
  public String getSerialNumber() {
    return device.getSerialNumber();
  }

  @Override
  public ImmutableSortedSet<Path> listDirRecursive(Path root) throws Exception {
    String lsOutput = executeCommandWithErrorChecking("ls -R " + root + " | cat");
    Set<Path> paths = new HashSet<>();
    Set<Path> dirs = new HashSet<>();
    Path currentDir = null;
    Pattern dirMatcher = Pattern.compile(":$");
    for (String line : Splitter.on(LINE_ENDING).omitEmptyStrings().split(lsOutput)) {
      if (dirMatcher.matcher(line).find()) {
        currentDir = root.relativize(Paths.get(line.substring(0, line.length() - 1)));
        dirs.add(currentDir);
      } else {
        assert currentDir != null;
        paths.add(currentDir.resolve(line));
      }
    }
    return ImmutableSortedSet.copyOf(Sets.difference(paths, dirs));
  }

  @Override
  public void rmFiles(String dirPath, Iterable<String> filesToDelete) throws Exception {
    String commandPrefix = "cd " + dirPath + " && rm ";
    // Add a fudge factor for separators and error checking.
    final int overhead = commandPrefix.length() + 100;
    for (List<String> rmArgs : chunkArgs(filesToDelete, MAX_ADB_COMMAND_SIZE - overhead)) {
      String command = commandPrefix + Joiner.on(' ').join(rmArgs);
      LOG.debug("Executing %s", command);
      executeCommandWithErrorChecking(command);
    }
  }

  @Override
  public AutoCloseable createForward() throws Exception {
    device.createForward(agentPort, agentPort);
    return () -> {
      try {
        device.removeForward(agentPort, agentPort);
      } catch (AdbCommandRejectedException e) {
        LOG.warn(e, "Failed to remove adb forward on port %d for device %s", agentPort, device);
        eventBus.post(
            ConsoleEvent.warning(
                "Failed to remove adb forward %d. This is not necessarily a problem\n"
                    + "because it will be recreated during the next exopackage installation.\n"
                    + "See the log for the full exception.",
                agentPort));
      }
    };
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractRapidInstallMode {
    abstract String getIpAddress();
  }

  private Optional<RapidInstallMode> getRapidInstallMode() {
    if (device.isEmulator() && rapidInstallTypes.contains("emu")) {
      return Optional.of(RapidInstallMode.builder().setIpAddress("10.0.2.2").build()); // NOPMD
    } else if (isLocalTransport() && rapidInstallTypes.contains("tcp")) {
      String hostIpAddr = device.getSerialNumber().replaceAll("\\.[0-9:]+$", ".1");
      return Optional.of(RapidInstallMode.builder().setIpAddress(hostIpAddr).build());
    }
    return Optional.empty();
  }

  @Override
  public void installFiles(String filesType, Map<Path, Path> installPaths) throws Exception {
    Optional<RapidInstallMode> rapidInstallMode = getRapidInstallMode();
    if (rapidInstallMode.isPresent()) {
      doRapidInstall(rapidInstallMode.get(), filesType, installPaths);
    } else {
      doMultiInstall(filesType, installPaths);
    }
  }

  private void doMultiInstall(String filesType, Map<Path, Path> installPaths) throws Exception {
    Closer closer = Closer.create();
    BuckInitiatedInstallReceiver receiver =
        new BuckInitiatedInstallReceiver(closer, filesType, installPaths);

    String command =
        "umask 022 && "
            + agent.get().getAgentCommand()
            + "multi-receive-file "
            + "-"
            + " "
            + agentPort
            + " "
            + "1"
            + ECHO_COMMAND_SUFFIX;
    LOG.debug("Executing %s", command);

    // If we fail to execute the command, stash the exception.  My experience during development
    // has been that the exception from checkReceiverOutput is more actionable.
    Exception shellException = null;
    try {
      device.executeShellCommand(command, receiver);
    } catch (Exception e) {
      shellException = e;
    }

    // Close the client socket, if we opened it.
    closer.close();

    if (receiver.getError().isPresent()) {
      Exception prev = shellException;
      shellException = receiver.getError().get();
      if (prev != null) {
        shellException.addSuppressed(prev);
      }
    }

    try {
      checkReceiverOutput(command, receiver);
    } catch (Exception e) {
      if (shellException != null) {
        e.addSuppressed(shellException);
      }
      throw e;
    }

    if (shellException != null) {
      throw shellException;
    }

    for (Path targetFileName : installPaths.keySet()) {
      chmod644(targetFileName);
    }
  }

  void doRapidInstall(
      RapidInstallMode rapidInstallMode, String filesType, Map<Path, Path> installPaths)
      throws Exception {
    Exception failure = null;
    String command;
    AgentInitiatedInstallReceiver receiver;

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      int nonce = (int) System.currentTimeMillis() & 0x7FFFFFFF;
      receiver = new AgentInitiatedInstallReceiver(serverSocket, nonce, filesType, installPaths);
      command =
          "umask 022 && "
              + agent.get().getAgentCommand()
              + "multi-receive-file "
              + rapidInstallMode.getIpAddress()
              + " "
              + port
              + " "
              + nonce
              + ECHO_COMMAND_SUFFIX;
      LOG.debug("Executing %s", command);

      // If we fail to execute the command, stash the exception.  My experience during development
      // has been that the exception from checkReceiverOutput is more actionable.
      try {
        device.executeShellCommand(command, receiver);
      } catch (Exception e) {
        failure = e;
      }
    }

    if (receiver.getError().isPresent()) {
      Exception prev = failure;
      failure = receiver.getError().get();
      if (prev != null) {
        failure.addSuppressed(prev);
      }
    }

    try {
      checkReceiverOutput(command, receiver);
    } catch (Exception e) {
      if (failure != null) {
        e.addSuppressed(failure);
      }
      failure = e;
    }
    if (failure != null) {
      throw failure;
    }

    for (Path targetFileName : installPaths.keySet()) {
      chmod644(targetFileName);
    }
  }

  private void chmod644(Path targetDevicePath)
      throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
          IOException {
    // The standard Java libraries on Android always create new files un-readable by other users.
    // We use the shell user or root to create these files, so we need to explicitly set the mode
    // to allow the app to read them.  Ideally, the agent would do this automatically, but
    // there's no easy way to do this in Java.  We can drop this if we drop support for the
    // Java agent.
    executeCommandWithErrorChecking("chmod 644 " + targetDevicePath);
  }

  @Override
  public void mkDirP(String dirpath) throws Exception {
    String mkdirCommand = agent.get().getMkDirCommand();

    executeCommandWithErrorChecking("umask 022 && " + mkdirCommand + " " + dirpath);
  }

  @Override
  public String getProperty(String name) throws Exception {
    return executeCommandWithErrorChecking("getprop " + name).trim();
  }

  @Override
  public List<String> getDeviceAbis() throws Exception {
    ImmutableList.Builder<String> abis = ImmutableList.builder();
    // Rare special indigenous to Lollipop devices
    String abiListProperty = getProperty("ro.product.cpu.abilist");
    if (!abiListProperty.isEmpty()) {
      abis.addAll(Splitter.on(',').splitToList(abiListProperty));
    } else {
      String abi1 = getProperty("ro.product.cpu.abi");
      if (abi1.isEmpty()) {
        throw new RuntimeException("adb returned empty result for ro.product.cpu.abi property.");
      }

      abis.add(abi1);
      String abi2 = getProperty("ro.product.cpu.abi2");
      if (!abi2.isEmpty()) {
        abis.add(abi2);
      }
    }

    return abis.build();
  }

  @Override
  public void killProcess(String processName) throws Exception {
    String packageName =
        processName.contains(":")
            ? processName.substring(0, processName.indexOf(':'))
            : processName;

    // Search through the running processes for a matching cmdline and kill it
    String command =
        String.format(
            "kill \"$(for p in /proc/[0-9]*; do "
                + "[[ -e $p/cmdline && $(<$p/cmdline) = '%s' ]] && echo `basename $p`; done)\"",
            processName);
    try {
      executeCommandWithErrorChecking(String.format("run-as %s %s", packageName, command));
      eventBus.post(ConsoleEvent.warning("Successfully terminated process " + processName));
    } catch (AdbHelper.CommandFailedException e) {
      eventBus.post(
          ConsoleEvent.warning(
              "No matching process found: %s. "
                  + "Check to ensure the process exists and is running.",
              processName));
    }
  }

  @Override
  public void sendBroadcast(String action, Map<String, String> stringExtras) throws Exception {
    StringBuilder commandBuilder = new StringBuilder();
    commandBuilder.append("am broadcast -a ").append(action);
    stringExtras
        .entrySet()
        .forEach(
            entry ->
                commandBuilder
                    .append(" --es ")
                    .append(Escaper.escapeAsShellString(entry.getKey()))
                    .append(" ")
                    .append(Escaper.escapeAsShellString(entry.getValue())));
    try {
      executeCommandWithErrorChecking(commandBuilder.toString());
      eventBus.post(ConsoleEvent.warning("Successfully sent broadcast " + action));
    } catch (AdbHelper.CommandFailedException e) {
      eventBus.post(ConsoleEvent.warning("Unable to send broadcast " + action));
    }
  }

  /**
   * Implementation of {@link com.android.ddmlib.IShellOutputReceiver} with helper functions to
   * parse output lines and figure out if a call to {@link IDevice#executeShellCommand(String,
   * com.android.ddmlib.IShellOutputReceiver)} succeeded.
   */
  public abstract static class ErrorParsingReceiver extends MultiLineReceiver {

    @Nullable private String errorMessage = null;

    /**
     * Look for an error message in {@code line}.
     *
     * @param line
     * @return an error message if {@code line} is indicative of an error, {@code null} otherwise.
     */
    @Nullable
    protected abstract String matchForError(String line);

    @Override
    public void processNewLines(String[] lines) {
      for (String line : lines) {
        if (line.length() > 0) {
          String err = matchForError(line);
          if (err != null) {
            errorMessage = err;
          }
        }
      }
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Nullable
    public String getErrorMessage() {
      return errorMessage;
    }
  }

  private class BuckInitiatedInstallReceiver extends CollectingOutputReceiver {
    /*
    The buck-initiated protocol:

    Buck will invoke the agent with a port, and the agent will begin listening on the port.
    The agent will generate a random session key of AgentUtil.TEXT_SECRET_KEY_SIZE hex characters.
    The agent will send the session key to stdout, followed by a newline.
    Buck will connect to the port.
    Recent versions of ADB on Linux will not properly forward data that Buck sends immediately,
    so the agent will accept the connection, then print "z1" (followed by a newline) to stdout
    to confirm that the connection has been initiated.
    Buck will send the session key to the agent, *without* a trailing newline.
    At this point, the connection is authenticated and can be used for multi-file transfer.

    Note that the secret key is meant to protect against a very specific attack:
    a malicious Android app on the device quickly connecting to the agent
    and sending infected files for installation.
     */

    private final Closer closer;
    private final String filesType;
    private final Map<Path, Path> installPaths;
    private boolean startedPayload;
    private boolean wrotePayload;
    @Nullable private OutputStream outToDevice;
    private Optional<Exception> error;

    BuckInitiatedInstallReceiver(Closer closer, String filesType, Map<Path, Path> installPaths) {
      this.closer = closer;
      this.filesType = filesType;
      this.installPaths = installPaths;
      this.startedPayload = false;
      this.wrotePayload = false;
      this.error = Optional.empty();
    }

    @Override
    public void addOutput(byte[] data, int offset, int length) {
      super.addOutput(data, offset, length);
      // On exceptions, we want to still collect the full output of the command (so we can get its
      // error code and possibly error message), so we just record that there was an error and only
      // send further output to the base receiver.
      if (error.isPresent()) {
        return;
      }
      try {
        if (!startedPayload && getOutput().length() >= AgentUtil.TEXT_SECRET_KEY_SIZE) {
          LOG.verbose("Got key: %s", getOutput().split("[\\r\\n]", 1)[0]);
          startedPayload = true;
          Socket clientSocket = new Socket("127.0.0.1", agentPort); // NOPMD
          closer.register(clientSocket);
          LOG.verbose("Connected");
          outToDevice = clientSocket.getOutputStream();
          closer.register(outToDevice);
          // Need to wait for client to acknowledge that we've connected.
        }
        if (!wrotePayload && getOutput().contains("z1")) {
          if (outToDevice == null) {
            throw new NullPointerException("outToDevice was null when protocol says it cannot be");
          }
          LOG.verbose("Got z1");
          wrotePayload = true;
          outToDevice.write(getOutput().substring(0, AgentUtil.TEXT_SECRET_KEY_SIZE).getBytes());
          LOG.verbose("Wrote key");
          multiInstallFilesToStream(outToDevice, filesType, installPaths);
          LOG.verbose("Wrote files");
        }
      } catch (IOException e) {
        error = Optional.of(e);
      }
    }

    public Optional<Exception> getError() {
      return error;
    }
  }

  private class AgentInitiatedInstallReceiver extends CollectingOutputReceiver {
    /*
    The agent-initiated protocol:

    Buck will invoke the agent with an IP, port, and nonce (31-bit).
    The agent will write a byte to stdout to trigger the ddmlib output receiver.
    The agent will connect to the IP and port (where Buck or a proxy will already be listening).
    The agent will write the nonce to the connection as a 4-byte big endian value.
    The connection will then be used for a multi-file transfer.

    Note that the nonce is not meant for security purposes.
    It will be used to avoid confusing streams when tunnelling the connection over USB.
     */

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;

    private final ServerSocket serverSocket;
    private final int nonce;
    private String filesType;
    private final Map<Path, Path> installPaths;
    private Optional<Exception> error = Optional.empty();
    private boolean startedSend = false;

    AgentInitiatedInstallReceiver(
        ServerSocket serverSocket, int nonce, String filesType, Map<Path, Path> installPaths) {
      this.serverSocket = serverSocket;
      this.nonce = nonce;
      this.filesType = filesType;
      this.installPaths = installPaths;
    }

    @Override
    public void addOutput(byte[] data, int offset, int length) {
      super.addOutput(data, offset, length);
      // On exceptions, we want to still collect the full output of the command (so we can get its
      // error code and possibly error message), so we just record that there was an error and only
      // send further output to the base receiver.
      if (error.isPresent()) {
        return;
      }

      // The receiver will send us a byte or so to indicate its intent to connect.
      // Only try to listen for it once.
      if (!startedSend) {
        startedSend = true;
        try {
          sendFiles();
        } catch (IOException e) {
          error = Optional.of(e);
        }
      }
    }

    private void sendFiles() throws IOException {
      serverSocket.setSoTimeout(CONNECT_TIMEOUT_MS);
      try (Socket connectionSocket = serverSocket.accept()) {
        connectionSocket.setSoTimeout(READ_TIMEOUT_MS);
        byte[] readBuffer = new byte[4];
        int got = connectionSocket.getInputStream().read(readBuffer);
        if (got != readBuffer.length) {
          throw new RuntimeException("Short read from agent.");
        }
        int gotNonce = Ints.fromByteArray(readBuffer);
        if (gotNonce != nonce) {
          throw new RuntimeException(
              String.format("Got wrong nonce from agent.  Expected %d, got %d.", nonce, gotNonce));
        }

        // TODO(dreiss): Use write timeouts.
        OutputStream stream = connectionSocket.getOutputStream();
        multiInstallFilesToStream(stream, filesType, installPaths);
      }
    }

    Optional<Exception> getError() {
      return error;
    }
  }

  /*
  The multi-file install protocol:

  First, a trusted byte stream from Buck to the agent will be established.
  Buck will transmit a sequence of files to the agent.
  Each file is preceded by a header.
  The header starts with a 4-character uppercase hex number,  which is the size of the header
  in bytes, starting from after the first space, followed by a space.
  Next is file size as ASCII text followed by a space.
  Next is the name of the file that the agent should write to, followed by a newline.
  The full file contents follows the newline immediately, with no terminator.
  The next file header begins immediately.
  The agent recognizes two special file names, which must always have size 0.
  "--continue" indicates that no file should be written.
  This might be used in the future to avoid read timeouts.
  "--complete" indicates that the transmission is complete, and the agent should exit.
   */
  private void multiInstallFilesToStream(
      OutputStream stream, String filesType, Map<Path, Path> installPaths) throws IOException {
    for (Map.Entry<Path, Path> entry : installPaths.entrySet()) {
      Path destination = entry.getKey();
      Path source = entry.getValue();
      try (SimplePerfEvent.Scope ignored =
          SimplePerfEvent.scope(eventBus, "install_" + filesType)) {
        // Slurp the file into RAM to make sure we know how many bytes we are getting.
        byte[] bytes = Files.readAllBytes(source);
        byte[] restOfHeader = (bytes.length + " " + destination + "\n").getBytes(Charsets.UTF_8);
        byte[] headerPrefix = String.format("%04X ", restOfHeader.length).getBytes(Charsets.UTF_8);
        stream.write(headerPrefix);
        stream.write(restOfHeader);
        stream.write(bytes);
      }
    }
    stream.write("000D 0 --complete\n".getBytes(Charsets.UTF_8));
    stream.flush();
  }
}
