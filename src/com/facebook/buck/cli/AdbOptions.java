/*
 * Copyright 2012-present Facebook, Inc.
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

import com.google.common.annotations.VisibleForTesting;

import org.kohsuke.args4j.Option;

import javax.annotation.Nullable;

public class AdbOptions {

  @VisibleForTesting static final String EMULATOR_MODE_LONG_ARG = "--emulator";
  @VisibleForTesting static final String EMULATOR_MODE_SHORT_ARG = "-e";
  @VisibleForTesting static final String DEVICE_MODE_LONG_ARG = "--device";
  @VisibleForTesting static final String DEVICE_MODE_SHORT_ARG = "-d";
  @VisibleForTesting static final String MULTI_INSTALL_MODE_LONG_ARG = "--all";
  @VisibleForTesting static final String MULTI_INSTALL_MODE_SHORT_ARG = "-x";
  @VisibleForTesting static final String SERIAL_NUMBER_LONG_ARG = "--serial";
  @VisibleForTesting static final String SERIAL_NUMBER_SHORT_ARG = "-s";
  @VisibleForTesting static final String ADB_THREADS_LONG_ARG = "--adb-threads";
  @VisibleForTesting static final String ADB_THREADS_SHORT_ARG = "-T";

  @Option(
      name = EMULATOR_MODE_LONG_ARG,
      aliases = { EMULATOR_MODE_SHORT_ARG },
      usage = "Use this option to install .apk on emulators only."
  )
  private boolean useEmulatorsOnlyMode;

  @Option(
      name = DEVICE_MODE_LONG_ARG,
      aliases = { DEVICE_MODE_SHORT_ARG },
      usage = "Use this option to install .apk on real devices only."
  )
  private boolean useRealDevicesOnlyMode;

  @Option(
      name = MULTI_INSTALL_MODE_LONG_ARG,
      aliases =  { MULTI_INSTALL_MODE_SHORT_ARG },
      usage = "Install .apk on all connected devices and/or emulators (multi-install mode)"
  )
  private boolean multiInstallMode;

  @Option(
      name = SERIAL_NUMBER_LONG_ARG,
      aliases = { SERIAL_NUMBER_SHORT_ARG },
      metaVar = "<serial-number>",
      usage = "Install .apk on device or emulator with specific serial number."
  )
  @Nullable
  private String serialNumber;

  @Option(
      name = ADB_THREADS_LONG_ARG,
      aliases = { ADB_THREADS_SHORT_ARG },
      usage = "Number of threads to use for adb operations. "+
              "Defaults to number of connected devices.")
  private int adbThreadCount = 0;

  public boolean isEmulatorsOnlyModeEnabled() {
    return useEmulatorsOnlyMode;
  }

  public boolean isRealDevicesOnlyModeEnabled() {
    return useRealDevicesOnlyMode;
  }

  public boolean isMultiInstallModeEnabled() {
    return multiInstallMode;
  }

  public @Nullable String getSerialNumber() {
    return serialNumber;
  }

  public boolean hasSerialNumber() {
    return serialNumber != null;
  }

  public int getAdbThreadCount() {
    return adbThreadCount;
  }
}
