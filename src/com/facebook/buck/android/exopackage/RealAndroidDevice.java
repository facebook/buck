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

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.MoreSuppliers;
import com.github.luben.zstd.ZstdOutputStream;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;
import com.google.common.io.CountingOutputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

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

  // constants for making requests to the adb daemon
  private static final String CHARSET_NAME = "ISO-8859-1";
  private static final int DEFAULT_TIMEOUT = 1000; // 1000ms
  private static final Charset DEFAULT_ENCODING;

  static {
    try {
      DEFAULT_ENCODING = Charset.forName(CHARSET_NAME);
    } catch (UnsupportedCharsetException e) {
      throw new HumanReadableException("Unsupported Charset name: " + CHARSET_NAME);
    }
  }

  private static final byte[] ID_DATA = "DATA".getBytes(DEFAULT_ENCODING);
  private static final byte[] ID_DONE = "DONE".getBytes(DEFAULT_ENCODING);
  private static final byte[] ID_SEND = "SEND".getBytes(DEFAULT_ENCODING);
  private static final int SYNC_DATA_MAX = 64 * 1024; // 64KB

  private final BuckEventBus eventBus;
  private final IDevice device;
  private final Console console;
  private final Supplier<ExopackageAgent> agent;
  private final int agentPort;
  private final boolean isZstdCompressionEnabled;

  public RealAndroidDevice(
      BuckEventBus eventBus,
      IDevice device,
      Console console,
      @Nullable Path agentApkPath,
      int agentPort,
      boolean alwaysUseJavaAgent,
      boolean isZstdCompressionEnabled) {
    this.eventBus = eventBus;
    this.device = device;
    this.console = console;
    this.agent =
        MoreSuppliers.memoize(
            () ->
                ExopackageAgent.installAgentIfNecessary(
                    eventBus,
                    this,
                    Objects.requireNonNull(agentApkPath, "Agent not configured for this device."),
                    alwaysUseJavaAgent));
    this.agentPort = agentPort;
    this.isZstdCompressionEnabled = isZstdCompressionEnabled;
  }

  @VisibleForTesting
  public RealAndroidDevice(BuckEventBus buckEventBus, IDevice device, Console console) {
    this(buckEventBus, device, console, null, -1, true, true);
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
      // Ignore warnings/other info that may come before the pm path output
      if (line.startsWith(pmPathPrefix)) {
        pmPath = line;
        break;
      }
    }

    if (pmPath == null || !pmPath.contains(packageName)) {
      LOG.warn("unable to locate package path for [" + packageName + "]");
      return Optional.empty();
    }

    String packagePrefix = "  Package [" + packageName + "] (";
    String otherPrefix = "  Package [";
    boolean sawPackageLine = false;
    Splitter splitter = Splitter.on('=').limit(2);

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

    Objects.requireNonNull(codePath, "Could not find codePath");
    Objects.requireNonNull(resourcePath, "Could not find resourcePath");
    Objects.requireNonNull(nativeLibPath, "Could not find nativeLibraryPath");
    Objects.requireNonNull(versionCode, "Could not find versionCode");
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
  public String deviceStartIntent(AndroidIntent intent) {
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
      device.executeShellCommand(
          AndroidIntent.getAmStartCommand(intent),
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
            String.format(
                "rm -rf %s/%s", ExopackageInstaller.EXOPACKAGE_INSTALL_ROOT, packageName));
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
    String name = getNameForDisplay();

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
      String fullMessage =
          "============================================================\n"
              + '\n'
              + "HEY! LISTEN!\n"
              + '\n'
              + "The /data/local/tmp directory on your device isn't fully-functional.\n"
              + "Here's some extra info:\n"
              + logMessage
              + "============================================================\n";
      console.getStdErr().println(fullMessage);
    }

    return true;
  }

  @Override
  public boolean installApkOnDevice(
      File apk, boolean installViaSd, boolean quiet, boolean verifyTempWritable) {
    String name = getNameForDisplay();

    if (verifyTempWritable && !isDeviceTempWritable(name)) {
      return false;
    }

    LOG.debug("Installing %s on %s, installViaSd=%s", apk, name, installViaSd);
    long start = System.currentTimeMillis();
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
      long elapsed = System.currentTimeMillis() - start;
      double kbps = (apk.length() / 1024.0) / (elapsed / 1000.0);
      LOG.debug("Installed %s (%,d bytes) in %,dms (%,.2f kB/s)", apk, apk.length(), elapsed, kbps);
      return true;
    } catch (InstallException ex) {
      console.printBuildFailure(String.format("Failed to install apk on %s.", name));
      ex.printStackTrace(console.getStdErr());
      return false;
    }
  }

  private String getNameForDisplay() {
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
    return name;
  }

  /**
   * The -d flag is only recognized on API level 17 and above. It was added to android/base in
   * 7767eac3232ba2fb9828766813cdb481d6a97584. The next release was jb-mr1-release,
   * b361b3043bb351ab175ae1ff3dae4de9fe31d42b.
   */
  private boolean supportsInstallDowngrade() {
    String value = device.getProperty("ro.build.version.sdk");
    try {
      if (Integer.parseInt(value) >= 17) {
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
  public void rmFiles(String dirPath, Iterable<String> filesToDelete) {
    try {
      try {
        rmFilesWithFlags(dirPath, filesToDelete, "-f");
      } catch (AdbHelper.CommandFailedException e) {
        if (!e.output.contains("rm failed for -f")) {
          throw e;
        }
        LOG.debug("Deleting files failed, retrying without `-f`.");
        rmFilesWithFlags(dirPath, filesToDelete, "");
      }
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(
          e, "When deleting files on the device %s.", getNameForDisplay());
    }
  }

  private void rmFilesWithFlags(String dirPath, Iterable<String> filesToDelete, String flags)
      throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
          IOException {
    String commandPrefix = String.format("cd %s && rm %s ", dirPath, flags);
    // Add a fudge factor for separators and error checking.
    int overhead = commandPrefix.length() + 100;
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

  @Override
  public void installFiles(String filesType, Map<Path, Path> installPaths) throws Exception {
    if (this.isZstdCompressionEnabled) {
      try {
        doMultiInstall(filesType, installPaths, /* tryZstdCompressionIfPossible */ true);
        return;
      } catch (Exception e) {
        LOG.warn(e, "doMultiInstall with zstd compression failed");
      }
    }
    try {
      doMultiInstall(filesType, installPaths, /* tryZstdCompressionIfPossible */ false);
    } catch (Exception e) {
      LOG.warn(
          e,
          "doMultiInstall without zstd compression failed, falling back to doMultiInstallViaADB");
      doMultiInstallViaADB(installPaths);
    }
  }

  private void writeAllToChannel(SocketChannel chan, ByteBuffer buf) throws HumanReadableException {
    try {
      chan.write(buf);
    } catch (IOException e) {
      throw new HumanReadableException("Write timed out");
    }
  }

  private void readAllFromChannel(SocketChannel chan, ByteBuffer buf)
      throws HumanReadableException {
    try {
      chan.read(buf);
    } catch (IOException e) {
      throw new HumanReadableException("Read timed out");
    }
  }

  private SocketChannel getSyncService() throws Exception {
    // Create a socket and send the necessary requests to adb daemon
    SocketChannel chan = SocketChannel.open(AndroidDebugBridge.getSocketAddress());

    // Set a timeout for the blocking channel
    chan.socket().setSoTimeout(DEFAULT_TIMEOUT);

    // Set the channel to be blocking
    chan.configureBlocking(true);

    String msg = "host:transport:" + device.getSerialNumber();
    byte[] device_query = formAdbRequest(msg); // req = length + command

    writeAllToChannel(chan, ByteBuffer.wrap(device_query));
    byte[] resp = readResp(chan, 4);

    if (!isOkay(resp)) {
      throw new HumanReadableException(
          "ADB daemon rejected switching connection to " + device.getSerialNumber());
    }

    byte[] sync_req = formAdbRequest("sync:");

    ByteBuffer b = ByteBuffer.wrap(sync_req);
    writeAllToChannel(chan, b);

    if (!isOkay(readResp(chan, 4))) {
      throw new HumanReadableException(
          "ADB daemon rejected starting sync service to " + device.getSerialNumber());
    }

    return chan;
  }

  @SuppressWarnings("PMD.AvoidUsingOctalValues")
  private void pushFile(String localPath, String remotePath, SocketChannel chan) throws Exception {
    byte[] remotePathContent = remotePath.getBytes(DEFAULT_ENCODING);
    File f = new File(localPath);
    FileInputStream fis = new FileInputStream(f);
    BufferedInputStream bis = new BufferedInputStream(fis);
    byte[] send_msg =
        createSendFileReq(ID_SEND, remotePathContent, 0644); // set the correct permission
    writeAllToChannel(chan, ByteBuffer.wrap(send_msg));

    byte[] dataBuffer = new byte[SYNC_DATA_MAX + 8];
    System.arraycopy(ID_DATA, 0, dataBuffer, 0, ID_DATA.length); // Write the data header

    // real transfer part

    while (true) {

      int readCount =
          bis.read(dataBuffer, 8, SYNC_DATA_MAX); // Give 8 bytes of space to command + length

      if (readCount == -1) {
        // we reached the end of the file
        break;
      }

      swap32bitsToArray(readCount, dataBuffer, 4);
      writeAllToChannel(
          chan, ByteBuffer.wrap(dataBuffer, 0, readCount + 8)); // 8 bytes for DATA + length
    }

    bis.close();

    long time = f.lastModified() / 1000;
    byte[] done_msg = createAdbdReq(ID_DONE, (int) time);
    writeAllToChannel(chan, ByteBuffer.wrap(done_msg));

    byte[] result = readResp(chan, 8); // 4-byte ID + 4-byte length

    if (!isOkay(result)) {
      throw new HumanReadableException(
          "Encountered \""
              + readErrorMessage(chan, result)
              + "\" when sending file "
              + localPath
              + " --> "
              + remotePath);
    }
  }

  private static boolean isOkay(byte[] reply) {
    return reply[0] == (byte) 'O'
        && reply[1] == (byte) 'K'
        && reply[2] == (byte) 'A'
        && reply[3] == (byte) 'Y';
  }

  private String readErrorMessage(SocketChannel chann, byte[] reply) throws Exception {
    // Read the 4-byte length at the end of the reply
    int length = ByteBuffer.wrap(reply, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    return new String(readResp(chann, length));
  }

  private static byte[] formAdbRequest(String req) {
    String resultStr = String.format("%04X%s", req.length(), req);
    byte[] result;
    result = resultStr.getBytes(DEFAULT_ENCODING);
    assert result.length == req.length() + 4;
    return result;
  }

  private static byte[] createAdbdReq(byte[] command, int value) {
    byte[] array = new byte[8];

    System.arraycopy(command, 0, array, 0, 4);
    swap32bitsToArray(value, array, 4);

    return array;
  }

  private static void swap32bitsToArray(int value, byte[] dest, int offset) {
    dest[offset] = (byte) (value & 0x000000FF);
    dest[offset + 1] = (byte) ((value & 0x0000FF00) >> 8);
    dest[offset + 2] = (byte) ((value & 0x00FF0000) >> 16);
    dest[offset + 3] = (byte) ((value & 0xFF000000) >> 24);
  }

  private static byte[] createSendFileReq(byte[] command, byte[] path, int mode) {
    // make the mode into a string
    String modeStr = "," + (mode & 777);
    byte[] modeContent = modeStr.getBytes(DEFAULT_ENCODING);

    byte[] array = new byte[8 + path.length + modeContent.length];

    System.arraycopy(command, 0, array, 0, 4);
    swap32bitsToArray(
        path.length + modeContent.length,
        array,
        4); // used for complying with the little endian requirement
    System.arraycopy(path, 0, array, 8, path.length);
    System.arraycopy(modeContent, 0, array, 8 + path.length, modeContent.length);
    return array;
  }

  private byte[] readResp(SocketChannel chan, int length) throws Exception {
    byte[] reply = new byte[length];
    ByteBuffer b_reply = ByteBuffer.wrap(reply);
    readAllFromChannel(chan, b_reply);

    return reply;
  }

  private void doMultiInstallViaADB(Map<Path, Path> installPaths) throws Exception {
    SocketChannel chan = getSyncService();
    for (Map.Entry<Path, Path> entry : installPaths.entrySet()) {
      String source = entry.getValue().toString();
      String destination = entry.getKey().toString();
      pushFile(source, destination, chan);
    }

    chan.close();
  }

  private void doMultiInstall(
      String filesType, Map<Path, Path> installPaths, boolean tryZstdCompressionIfPossible)
      throws Exception {
    if (installPaths.isEmpty()) {
      return;
    }

    // We only do zstd compression for the Java agent.
    boolean doZstdCompression = tryZstdCompressionIfPossible && !agent.get().isUseNativeAgent();

    String javaLibraryPath = null;
    if (doZstdCompression) {
      try {
        Optional<Path> zstdRelativePath =
            listDirRecursive(Paths.get(agent.get().getNativePath())).stream()
                .filter(path -> path.endsWith("libzstd-jni.so"))
                .findFirst()
                .map(Path::getParent);
        if (!zstdRelativePath.isPresent()) {
          doZstdCompression = false;
        } else {
          javaLibraryPath =
              agent.get().getNativePath() + File.separator + zstdRelativePath.get().toString();
        }
      } catch (IOException e) {
        LOG.info(e, "Couldn't list agent's native libs");
        doZstdCompression = false;
      }
    }

    Closer closer = Closer.create();
    BuckInitiatedInstallReceiver receiver =
        new BuckInitiatedInstallReceiver(closer, filesType, installPaths, doZstdCompression);

    String command =
        "umask 022 && "
            + agent.get().getAgentCommand(javaLibraryPath)
            + "multi-receive-file "
            + agentPort
            + " "
            + doZstdCompression
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
    private boolean doZstdCompression;

    BuckInitiatedInstallReceiver(
        Closer closer, String filesType, Map<Path, Path> installPaths, boolean doZstdCompression) {
      this.closer = closer;
      this.filesType = filesType;
      this.installPaths = installPaths;
      this.startedPayload = false;
      this.wrotePayload = false;
      this.error = Optional.empty();
      this.doZstdCompression = doZstdCompression;
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
          if (doZstdCompression) {
            CountingOutputStream countingOutputStream = new CountingOutputStream(outToDevice);
            ZstdOutputStream zstdOutputStream = new ZstdOutputStream(countingOutputStream);
            zstdOutputStream.setCloseFrameOnFlush(true);
            try {
              multiInstallFilesToStream(zstdOutputStream, filesType, installPaths);
              LOG.info("Size of compressed bytes is %,d", countingOutputStream.getCount());
            } finally {
              zstdOutputStream.close();
            }
          } else {
            multiInstallFilesToStream(outToDevice, filesType, installPaths);
          }
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
  public String getWindowManagerProperty(String name) throws Exception {
    String result = executeCommandWithErrorChecking("wm " + name).trim();
    String[] split = result.split(":");
    if (split.length < 2) {
      return result;
    }
    return split[1].trim();
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
    long startTime = System.currentTimeMillis();
    long totalByteCount = 0;
    for (Map.Entry<Path, Path> entry : installPaths.entrySet()) {
      Path destination = entry.getKey();
      Path source = entry.getValue();
      try (SimplePerfEvent.Scope ignored =
          SimplePerfEvent.scope(eventBus.isolated(), "install_" + filesType)) {
        // Slurp the file into RAM to make sure we know how many bytes we are getting.
        byte[] bytes = Files.readAllBytes(source);
        byte[] restOfHeader =
            (bytes.length + " " + destination + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] headerPrefix =
            String.format("%04X ", restOfHeader.length).getBytes(StandardCharsets.UTF_8);
        stream.write(headerPrefix);
        stream.write(restOfHeader);
        stream.write(bytes);
        stream.flush();
        totalByteCount += bytes.length;
      }
    }
    long elapsedMillis = System.currentTimeMillis() - startTime;

    double kbps = (totalByteCount / 1024.0) / (elapsedMillis / 1000.0);
    LOG.info(
        "%s: Transferred %s files (%,d bytes) in %,dms (%,.2f kB/s)",
        filesType, installPaths.size(), totalByteCount, elapsedMillis, kbps);

    stream.write("000D 0 --complete\n".getBytes(StandardCharsets.UTF_8));
    stream.flush();
  }
}
