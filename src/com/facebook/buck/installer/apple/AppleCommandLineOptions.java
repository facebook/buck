/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.installer.apple;

import com.facebook.buck.android.device.TargetDeviceOptions;
import com.facebook.buck.installer.common.ConsumeAllOptionsHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

/**
 * Constructs the Command Line Options to Support Apple Install. The majority of these were copied
 * from {@code com.facebook.buck.cli.TargetDeviceCommandLineOptions}.
 */
public class AppleCommandLineOptions {

  private static final String RUN_LONG_ARG = "--run";
  private static final String RUN_SHORT_ARG = "-r";

  @Option(
      name = RUN_LONG_ARG,
      aliases = {RUN_SHORT_ARG},
      usage = "To run an apple app")
  public boolean isRun = false;

  @Option(
      name = "--",
      usage = "Arguments passed when running with -r. Only valid for Apple targets.",
      handler = ConsumeAllOptionsHandler.class,
      depends = "-r")
  private final List<String> runArgs = new ArrayList<>();

  private static final String WAIT_FOR_DEBUGGER_LONG_ARG = "--wait-for-debugger";
  private static final String WAIT_FOR_DEBUGGER_SHORT_ARG = "-w";

  @Option(
      name = WAIT_FOR_DEBUGGER_LONG_ARG,
      aliases = {WAIT_FOR_DEBUGGER_SHORT_ARG},
      usage = "Have the launched process wait for the debugger")
  private final boolean waitForDebugger = false;

  private static final String DEVICE_MODE_SHORT_ARG = "-d";
  private static final String DEVICE_MODE_LONG_ARG = "--device";

  @Option(
      name = DEVICE_MODE_LONG_ARG,
      aliases = {DEVICE_MODE_SHORT_ARG},
      usage = "Use this option to use real devices only.")
  private boolean useRealDevicesOnlyMode;

  static final String SERIAL_NUMBER_SHORT_ARG = "-s";
  static final String SERIAL_NUMBER_LONG_ARG = "--serial";
  static final String UDID_ARG = "--udid";

  @Option(
      name = SERIAL_NUMBER_LONG_ARG,
      aliases = {SERIAL_NUMBER_SHORT_ARG, UDID_ARG},
      forbids = SIMULATOR_NAME_LONG_ARG,
      metaVar = "<serial-number>",
      usage = "Use device with specific serial or UDID number.")
  @Nullable
  private String serialNumber;

  static final String SIMULATOR_NAME_SHORT_ARG = "-n";
  static final String SIMULATOR_NAME_LONG_ARG = "--simulator-name";

  @Option(
      name = SIMULATOR_NAME_LONG_ARG,
      aliases = {SIMULATOR_NAME_SHORT_ARG},
      forbids = SERIAL_NUMBER_LONG_ARG,
      metaVar = "<name>",
      usage = "Use simulator with specific name (Apple only).")
  @Nullable
  private String simulatorName;

  @Option(name = "--named-pipe", usage = "unix domain socket used for connection if available")
  @Nullable
  private String unixDomainSocket;

  @Option(
      name = "--tcp-port",
      usage = "TCP port used for connection in case TCP protocol is chosen")
  private int tcpPort = 50055;

  @Option(name = "--idb_path", usage = "Use this option to set the idb path for the install")
  private String idbPath = "/usr/local/bin/idb";

  public AppleCommandLineOptions() {}

  public boolean getRun() {
    return isRun;
  }

  public List<String> getRunArgs() {
    return runArgs;
  }

  public boolean getWaitForDebugger() {
    return waitForDebugger;
  }

  public Optional<String> getSerialNumber() {
    return Optional.ofNullable(serialNumber);
  }

  public Optional<String> getSimulatorName() {
    return Optional.ofNullable(simulatorName);
  }

  public String getUnixDomainSocket() {
    return unixDomainSocket;
  }

  public int getTcpPort() {
    return tcpPort;
  }

  public String getIdbPath() {
    return idbPath;
  }

  /** Gets Target Device Options for Install */
  public TargetDeviceOptions getTargetDeviceOptions() {
    return new TargetDeviceOptions(false, useRealDevicesOnlyMode, getSerialNumber());
  }
}
