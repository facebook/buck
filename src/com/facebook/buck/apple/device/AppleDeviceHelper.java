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

package com.facebook.buck.apple.device;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;

import java.nio.file.Path;

import java.util.EnumSet;
import java.util.Set;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppleDeviceHelper {
  private static final Logger LOG = Logger.get(AppleDeviceHelper.class);
  private static final Pattern DEVICE_DESCRIPTION_PATTERN =
      Pattern.compile("([a-f0-9]{40}) : (.*)$");


  private final ProcessExecutor processExecutor;
  private final Path deviceHelperPath;

  public AppleDeviceHelper(
      ProcessExecutor processExecutor,
      Path deviceHelperPath) {
    this.processExecutor = processExecutor;
    this.deviceHelperPath = deviceHelperPath;
  }

  /**
   * Runs the helper program to enumerate all currently-connected devices.
   *
   * @return A ImmutableMap with entries where the key is the UDID of the device, and the value
   * is a human readable description (e.g. "iPhone (iPhone 5S) (USB)")
   * @throws IOException
   * @throws InterruptedException
   */
  public ImmutableMap<String, String> getConnectedDevices() throws IOException,
      InterruptedException {
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    deviceHelperPath.toString(), "-l"))
            .build();
    // Must specify that stdout is expected or else output may be wrapped in Ansi escape chars.
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor.Result result;
    try {
      result = processExecutor.launchAndExecute(
          processExecutorParams,
          options,
              /* stdin */ Optional.<String>absent(),
              /* timeOutMs */ Optional.<Long>of((long) 5000),
              /* timeOutHandler */ Optional.<Function<Process, Void>>absent());
    } catch (InterruptedException | IOException e) {
      LOG.warn("Could not execute device helper.");
      return ImmutableMap.of();
    }

    if (result.isTimedOut()) {
      throw new RuntimeException("Device helper failed: timed out");
    }

    if (result.getExitCode() != 0) {
      throw new RuntimeException("Device helper failed: " + result.getStderr());
    }

    Matcher matcher = DEVICE_DESCRIPTION_PATTERN.matcher(result.getStdout().get());
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    while (matcher.find()) {
      String udid = matcher.group(1);
      String description = matcher.group(2);
      builder.put(udid, description);
      LOG.debug("Found device: " + udid + ", " + description);
    }
    return builder.build();
  }

  /**
   * Attempts to install a bundle on the device.  The bundle must be code-signed already.
   *
   * @param udid        The identifier of the device
   * @param bundlePath  The path to the bundle root (e.g. {@code /path/to/Example.app/})
   * @return            true if successful, false otherwise.
   */
  public boolean installBundleOnDevice(String udid, Path bundlePath) {
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    deviceHelperPath.toString(), "-d", udid, "-t", bundlePath.toString()))
            .build();
    Set<ProcessExecutor.Option> options = EnumSet.of(
        ProcessExecutor.Option.PRINT_STD_OUT,
        ProcessExecutor.Option.PRINT_STD_ERR);
    ProcessExecutor.Result result;
    try {
      result = processExecutor.launchAndExecute(
          processExecutorParams,
          options,
              /* stdin */ Optional.<String>absent(),
              /* timeOutMs */ Optional.<Long>of((long) 60000),
              /* timeOutHandler */ Optional.<Function<Process, Void>>absent());
    } catch (InterruptedException | IOException e) {
      LOG.warn("Could not execute device helper.");
      return false;
    }

    if (result.isTimedOut()) {
      throw new RuntimeException("Device helper failed: timed out");
    }

    return (result.getExitCode() == 0);
  }

  /**
   * Attempts to run a bundle on the device.  The bundle must be installed already.
   *
   * @param udid        The identifier of the device
   * @param bundleID    The bundle ID (e.g. {@code com.example.DemoApp})
   * @return            true if successful, false otherwise.
   */
  public boolean runBundleOnDevice(String udid, String bundleID) {
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder()
            .setCommand(
                ImmutableList.of(
                    deviceHelperPath.toString(), "-d", udid, "--run", bundleID))
            .build();
    Set<ProcessExecutor.Option> options = EnumSet.of(
        ProcessExecutor.Option.PRINT_STD_OUT,
        ProcessExecutor.Option.PRINT_STD_ERR);
    ProcessExecutor.Result result;
    try {
      result = processExecutor.launchAndExecute(
          processExecutorParams,
          options,
              /* stdin */ Optional.<String>absent(),
              /* timeOutMs */ Optional.<Long>of((long) 60000),
              /* timeOutHandler */ Optional.<Function<Process, Void>>absent());
    } catch (InterruptedException | IOException e) {
      LOG.warn("Could not execute device helper.");
      return false;
    }

    if (result.isTimedOut()) {
      throw new RuntimeException("Device helper failed: timed out");
    }

    return (result.getExitCode() == 0);
  }
}
