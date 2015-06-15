/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.step.TargetDevice;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;

import javax.annotation.Nullable;

public class TargetDeviceOptions {

  private boolean useEmulatorsOnlyMode;

  private boolean useRealDevicesOnlyMode;

  @Nullable
  private String serialNumber;

  public TargetDeviceOptions() {
    this(false, false, null);
  }

  public TargetDeviceOptions(
      boolean useEmulatorsOnlyMode,
      boolean useRealDevicesOnlyMode,
      @Nullable String serialNumber) {
    this.useEmulatorsOnlyMode = useEmulatorsOnlyMode;
    this.useRealDevicesOnlyMode = useRealDevicesOnlyMode;
    this.serialNumber = serialNumber;
  }

  @VisibleForTesting
  TargetDeviceOptions(String serial) {
    this.serialNumber = serial;
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
        getSerialNumber());
    return Optional.of(device);
  }
}
