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

import com.facebook.buck.android.HasInstallableApk;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.io.Closeable;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * AndroidDevicesHelper provides a way to interact with multiple devices as AndroidDevices (rather
 * than IDevices).
 *
 * <p>All of ExopackageInstaller's interaction with devices and adb goes through this class and
 * AndroidDevice making it easy to provide different implementations in tests.
 */
@VisibleForTesting
public interface AndroidDevicesHelper extends Closeable {
  /**
   * This is basically the same as AdbHelper.AdbCallable except that it takes an AndroidDevice
   * instead of an IDevice.
   */
  interface AdbDeviceCallable {

    boolean apply(AndroidDevice device) throws Exception;
  }

  /** A simple wrapper around adbCall that will throw if adbCall returns false. */
  default void adbCallOrThrow(String description, AdbDeviceCallable func, boolean quiet)
      throws InterruptedException {
    if (!adbCall(description, func, quiet)) {
      throw new HumanReadableException(String.format("Error when running: <%s>", description));
    }
  }

  boolean adbCall(String description, AdbDeviceCallable func, boolean quiet)
      throws InterruptedException;

  ImmutableList<AndroidDevice> getDevices(boolean quiet) throws InterruptedException;

  /**
   * Install apk on all matching devices. This functions performs device filtering based on three
   * possible arguments:
   *
   * <p>-e (emulator-only) - only emulators are passing the filter -d (device-only) - only real
   * devices are passing the filter -s (serial) - only device/emulator with specific serial number
   * are passing the filter
   *
   * <p>If more than one device matches the filter this function will fail unless multi-install mode
   * is enabled (-x). This flag is used as a marker that user understands that multiple devices will
   * be used to install the apk if needed.
   */
  boolean installApk(
      SourcePathResolver pathResolver,
      HasInstallableApk hasInstallableApk,
      boolean installViaSd,
      boolean quiet,
      @Nullable String packageName)
      throws InterruptedException;

  /**
   * Uninstall apk from all matching devices.
   *
   * @see #installApk(SourcePathResolver, HasInstallableApk, boolean, boolean, String)
   */
  boolean uninstallApp(String packageName, boolean shouldKeepUserData) throws InterruptedException;

  void startActivity(
      SourcePathResolver pathResolver,
      HasInstallableApk hasInstallableApk,
      @Nullable String activity,
      boolean waitForDebugger)
      throws IOException, InterruptedException;
}
