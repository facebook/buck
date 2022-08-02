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

package com.facebook.buck.installer;

import com.google.common.annotations.VisibleForTesting;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

/**
 * Constructs the Command Line Options to Support Android Install. The majority of these were copied
 * from {@code com.facebook.buck.cli.TargetDeviceCommandLineOptions}.
 */
class AndroidCommandLineOptions {
  @VisibleForTesting public static final String EMULATOR_MODE_SHORT_ARG = "-e";

  @VisibleForTesting static final String EMULATOR_MODE_LONG_ARG = "--emulator";

  @Option(
      name = EMULATOR_MODE_LONG_ARG,
      aliases = {EMULATOR_MODE_SHORT_ARG},
      usage = "Use this option to use emulators only.")
  public boolean useEmulatorsOnlyMode;

  @VisibleForTesting static final String DEVICE_MODE_SHORT_ARG = "-d";
  @VisibleForTesting static final String DEVICE_MODE_LONG_ARG = "--device";

  @Option(
      name = DEVICE_MODE_LONG_ARG,
      aliases = {DEVICE_MODE_SHORT_ARG},
      usage = "Use this option to use real devices only.")
  public boolean useRealDevicesOnlyMode;

  @VisibleForTesting static final String SERIAL_NUMBER_SHORT_ARG = "-s";
  @VisibleForTesting static final String SERIAL_NUMBER_LONG_ARG = "--serial";
  static final String UDID_ARG = "--udid";

  @Option(
      name = SERIAL_NUMBER_LONG_ARG,
      aliases = {SERIAL_NUMBER_SHORT_ARG, UDID_ARG},
      metaVar = "<serial-number>",
      usage = "Use device or emulator with specific serial or UDID number.")
  @Nullable
  public String serialNumber;

  @VisibleForTesting static final String ADB_THREADS_LONG_ARG = "--adb-threads";
  @VisibleForTesting static final String ADB_THREADS_SHORT_ARG = "-T";

  @Option(
      name = ADB_THREADS_LONG_ARG,
      aliases = {ADB_THREADS_SHORT_ARG},
      usage =
          "Number of threads to use for adb operations. "
              + "Defaults to number of connected devices.")
  public int adbThreadCount = 0;

  @VisibleForTesting static final String MULTI_INSTALL_MODE_SHORT_ARG = "-x";
  @VisibleForTesting static final String MULTI_INSTALL_MODE_LONG_ARG = "--all-devices";

  @Option(
      name = MULTI_INSTALL_MODE_LONG_ARG,
      aliases = {MULTI_INSTALL_MODE_SHORT_ARG},
      usage = "Install .apk on all connected devices and/or emulators (multi-install mode)")
  public boolean multiInstallMode;

  @Option(name = "--named-pipe", usage = "unix domain socket used for connection if available")
  @Nullable
  public String unixDomainSocket;

  public int adbTimeout = 60_000;

  public AndroidCommandLineOptions() {}
}
