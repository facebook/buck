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

package com.facebook.buck.android.device;

import java.util.Optional;

public class TargetDeviceOptions {

  private boolean useEmulatorsOnlyMode;

  private boolean useRealDevicesOnlyMode;

  private Optional<String> serialNumber;

  public TargetDeviceOptions() {
    this(false, false, Optional.empty());
  }

  public TargetDeviceOptions(
      boolean useEmulatorsOnlyMode, boolean useRealDevicesOnlyMode, Optional<String> serialNumber) {
    this.useEmulatorsOnlyMode = useEmulatorsOnlyMode;
    this.useRealDevicesOnlyMode = useRealDevicesOnlyMode;
    this.serialNumber = serialNumber;
  }

  public boolean isEmulatorsOnlyModeEnabled() {
    return useEmulatorsOnlyMode;
  }

  public boolean isRealDevicesOnlyModeEnabled() {
    return useRealDevicesOnlyMode;
  }

  public Optional<String> getSerialNumber() {
    return serialNumber;
  }
}
