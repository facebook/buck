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

import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;

import com.android.ddmlib.IDevice;

public class ApkInstallStep implements Step {

  private final InstallableApk installableApk;

  public ApkInstallStep(
      InstallableApk apk) {
    this.installableApk = apk;
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    AdbHelper adbHelper = AdbHelper.get(context, true);
    if (adbHelper.getDevices(true).isEmpty()) {
      return 0;
    }

    if (!adbHelper.installApk(installableApk, false, true)) {
      return 1;
    }
    return 0;
  }

  @Override
  public String getShortName() {
    return "install apk";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder builder = new StringBuilder();

    try {
      AdbHelper adbHelper = AdbHelper.get(context, true);
      for (IDevice device : adbHelper.getDevices(true)) {
        if (builder.length() != 0) {
          builder.append("\n");
        }
        builder.append("adb -s ");
        builder.append(device.getSerialNumber());
        builder.append(" install ");
        builder.append(installableApk.getApkPath().toString());
      }
    } catch (InterruptedException e) {
    }
    return builder.toString();
  }

}
