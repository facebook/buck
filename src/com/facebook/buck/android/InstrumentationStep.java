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
import com.google.common.base.Optional;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner;

import java.io.IOException;
import java.nio.file.Path;

public class InstrumentationStep implements Step {

  private final InstallableApk apk;
  private final Path directoryForTestResults;

  private Optional<Long> testRuleTimeoutMs;

  public InstrumentationStep(
      InstallableApk apk,
      Path directoryForTestResults,
      Optional<Long> testRuleTimeoutMs) {
    this.apk = apk;
    this.directoryForTestResults = directoryForTestResults;
    this.testRuleTimeoutMs = testRuleTimeoutMs;
  }

  @Override
  public int execute(ExecutionContext context)
      throws
      IOException,
      InterruptedException {
    String packageName = AdbHelper.tryToExtractPackageNameFromManifest(
        apk,
        context);
    String testRunner = AdbHelper.tryToExtractInstrumentationTestRunnerFromManifest(
        apk,
        context);

    try {
      AdbHelper adbHelper = AdbHelper.get(context, true);
      for (IDevice device : adbHelper.getDevices(true)) {
        RemoteAndroidTestRunner runner = new RemoteAndroidTestRunner(
            packageName,
            testRunner,
            device);

        BuckXmlTestRunListener listener = new BuckXmlTestRunListener(device.getSerialNumber());
        listener.setReportDir(directoryForTestResults.toFile());
        runner.run(listener);
      }
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    } catch (TimeoutException|AdbCommandRejectedException|ShellCommandUnresponsiveException e) {
      return 1;
    }
    return 0;
  }

  @Override
  public String getShortName() {
    return "instrumentation test";
  }

  protected Optional<Long> getTimeout() {
    return testRuleTimeoutMs;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    String packageName = AdbHelper.tryToExtractPackageNameFromManifest(
        apk,
        context);
    String testRunner = AdbHelper.tryToExtractInstrumentationTestRunnerFromManifest(
        apk,
        context);

    StringBuilder builder = new StringBuilder();

    try {
      AdbHelper adbHelper = AdbHelper.get(context, true);
      for (IDevice device : adbHelper.getDevices(true)) {
        if (builder.length() != 0) {
          builder.append("\n");
        }
        builder.append("adb -s ");
        builder.append(device.getSerialNumber());
        builder.append(" shell am instrument -w ");
        builder.append(packageName);
        builder.append("/");
        builder.append(testRunner);
      }
    } catch (InterruptedException e) {
    }
    return builder.toString();
  }

}
