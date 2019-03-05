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

package com.facebook.buck.util.environment;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.ForwardingProcessListener;
import com.facebook.buck.util.ListeningProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mac OS X implementation for finding likely network states for diagnostic purposes. */
public class MacNetworkConfiguration {
  private static final Logger LOG = Logger.get(MacNetworkConfiguration.class);
  private static final long COMMAND_TIMEOUT_MS = 1000L;

  // Utility class, do not instantiate.
  private MacNetworkConfiguration() {}

  /** Returns a string representing likely active network; eg 'Wired', 'WiFi:<ssid>'. */
  public static Network getLikelyActiveNetwork() {
    try {
      for (String device : getDevicesByServiceOrder()) {
        if (isDeviceActive(device)) {
          Optional<String> ssid = getDeviceSSID(device);
          if (ssid.isPresent()) {
            return new Network(NetworkMedium.WIRELESS, ssid);
          }
          return new Network(NetworkMedium.WIRED);
        }
      }
      return new Network(NetworkMedium.UNKNOWN);
    } catch (InterruptedException e) {
      return new Network(NetworkMedium.UNKNOWN);
    }
  }

  static Pattern devicePattern = Pattern.compile("Device: ([^)]*)\\)");
  /** Returns a list of the network devices in order of their service priorities. */
  private static List<String> getDevicesByServiceOrder() throws InterruptedException {
    /*
    $ networksetup -listnetworkserviceorder
    An asterisk (*) denotes that a network service is disabled.
    (1) Display Ethernet (en6)
    (Hardware Port: Display Ethernet, Device: en6)

    (2) Wi-Fi
    (Hardware Port: Wi-Fi, Device: en0)
    */
    LOG.debug("Determine network service order and extract device names");
    String serviceOrderOutput = runNetworkSetupCommand("listnetworkserviceorder");
    Matcher matcher = devicePattern.matcher(serviceOrderOutput);
    List<String> devices = new ArrayList<String>();
    while (matcher.find()) {
      devices.add(matcher.group(1));
    }
    return devices;
  }

  static Pattern activePattern = Pattern.compile("Active: (.*)$");
  /** Indicates whether device is active (i.e. physically connected). */
  private static boolean isDeviceActive(String device) throws InterruptedException {
    /*
      $ networksetup -getMedia "en0"
      Current: autoselect
      Active: autoselect

      $ networksetup -getMedia "en6"
      Current: autoselect
      Active: none
    */
    LOG.debug("Determine active state of media for device");
    String mediaOutput = runNetworkSetupCommand("getMedia", device);
    Matcher matcher = activePattern.matcher(mediaOutput);
    return (matcher.find() && !matcher.group(1).equals("none"));
  }

  static Pattern ssidPattern = Pattern.compile("Current Wi-Fi Network: (.*)$");
  /** Gets the SSID of a device (sadly the most definitive way to determine wired vs wireless). */
  private static Optional<String> getDeviceSSID(String device) throws InterruptedException {
    /*
      $ networksetup -getairportnetwork "en0"
      Current Wi-Fi Network: lighthouse
      -- or
      $ networksetup -getairportnetwork "en0"
      You are not associated with an AirPort network.
      Wi-Fi power is currently off.

      $ networksetup -getairportnetwork "en6"
      en6 is not a Wi-Fi interface.
      ** Error: Error obtaining wireless information.
    */
    LOG.debug("Determine WiFi SSID of device");
    String mediaOutput = runNetworkSetupCommand("getairportnetwork", device);
    Matcher matcher = ssidPattern.matcher(mediaOutput);
    if (matcher.find()) {
      return Optional.of(matcher.group(1));
    }
    return Optional.empty();
  }

  private static String runNetworkSetupCommand(String subCommand) throws InterruptedException {
    return runNetworkSetupCommand(subCommand, "");
  }

  /** Naive `networksetup` invocation; returns non-empty string of stdout if all went well. */
  private static String runNetworkSetupCommand(String subCommand, String argument)
      throws InterruptedException {

    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    ForwardingProcessListener listener = new ForwardingProcessListener(stdout, stderr);

    ProcessExecutorParams params =
        ProcessExecutorParams.builder()
            .addCommand("networksetup")
            .addCommand(String.format("-%s", subCommand))
            .addCommand(argument)
            .build();

    ListeningProcessExecutor.LaunchedProcess process = null;
    try {
      process = executor.launchProcess(params, listener);
      if (executor.waitForProcess(process, COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS) != 0) {
        return "";
      }
      return stdout.toString();
    } catch (IOException e) {
      LOG.debug(e, "Exception while running networksetup command");
      return "";
    } finally {
      if (process != null) {
        executor.destroyProcess(process, /* force */ true);
      }
    }
  }
}
