/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.step.TargetDevice;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;

import org.kohsuke.args4j.Option;

import javax.annotation.Nullable;

public class TargetDeviceOptions {

  /**
   * If this environment variable is set, the device with the specified serial
   * number is targeted. The -s option overrides this.
   */
  @VisibleForTesting
  static final String SERIAL_NUMBER_ENV = "ANDROID_SERIAL";

  @VisibleForTesting public static final String EMULATOR_MODE_SHORT_ARG = "-e";
  @VisibleForTesting static final String EMULATOR_MODE_LONG_ARG = "--emulator";
  @Option(
      name = EMULATOR_MODE_LONG_ARG,
      aliases = {EMULATOR_MODE_SHORT_ARG},
      usage = "Use this option to use emulators only."
  )
  private boolean useEmulatorsOnlyMode;

  @VisibleForTesting static final String DEVICE_MODE_SHORT_ARG = "-d";
  @VisibleForTesting static final String DEVICE_MODE_LONG_ARG = "--device";
  @Option(
      name = DEVICE_MODE_LONG_ARG,
      aliases = {DEVICE_MODE_SHORT_ARG},
      usage = "Use this option to use real devices only."
  )
  private boolean useRealDevicesOnlyMode;

  @VisibleForTesting static final String SERIAL_NUMBER_SHORT_ARG = "-s";
  @VisibleForTesting static final String SERIAL_NUMBER_LONG_ARG = "--serial";
  @Option(
      name = SERIAL_NUMBER_LONG_ARG,
      aliases = {SERIAL_NUMBER_SHORT_ARG},
      metaVar = "<serial-number>",
      usage = "Use device or emulator with specific serial number."
  )
  @Nullable
  private String serialNumber;

  public TargetDeviceOptions() {
    this(getSerialNumberFromEnv());
  }

  @VisibleForTesting
  TargetDeviceOptions(String serial) {
    this.serialNumber = serial;
  }

  @VisibleForTesting
  @Nullable
  static String getSerialNumberFromEnv() {
    String serial;
    try {
      serial = System.getenv(SERIAL_NUMBER_ENV);
    } catch (SecurityException ex) {
      serial = null;
    }

    return serial;
  }

  public boolean isEmulatorsOnlyModeEnabled() {
    return useEmulatorsOnlyMode;
  }

  public boolean isRealDevicesOnlyModeEnabled() {
    return useRealDevicesOnlyMode;
  }

  @Nullable
  public String getSerialNumber() {
    return serialNumber;
  }

  public boolean hasSerialNumber() {
    return serialNumber != null;
  }

  public Optional<TargetDevice> getTargetDeviceOptional() {
    if (!hasSerialNumber() && !isEmulatorsOnlyModeEnabled() && !isRealDevicesOnlyModeEnabled()) {
      return Optional.absent();
    }

    TargetDevice device = new TargetDevice(
        isEmulatorsOnlyModeEnabled() ? TargetDevice.Type.EMULATOR : TargetDevice.Type.REAL_DEVICE,
        getSerialNumber()
    );
    return Optional.of(device);
  }
}
