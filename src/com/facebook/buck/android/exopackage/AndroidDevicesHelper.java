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

import com.google.common.annotations.VisibleForTesting;

/**
 * AndroidDevicesHelper provides a way to interact with multiple devices as AndroidDevices (rather
 * than IDevices).
 *
 * <p>All of ExopackageInstaller's interaction with devices and adb goes through this class and
 * AndroidDevice making it easy to provide different implementations in tests.
 */
@VisibleForTesting
public interface AndroidDevicesHelper {
  /**
   * This is basically the same as AdbHelper.AdbCallable except that it takes an AndroidDevice
   * instead of an IDevice.
   */
  interface AdbDeviceCallable {
    boolean apply(AndroidDevice device) throws Exception;
  }

  boolean adbCall(String description, AdbDeviceCallable func, boolean quiet)
      throws InterruptedException;
}
