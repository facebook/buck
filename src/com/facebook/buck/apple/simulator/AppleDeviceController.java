/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.apple.simulator;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Utility class to control Apple Devices (both simulators and physical devices). Examples include
 * booting them, installing and launching bundles inside of the devices.
 */
public class AppleDeviceController {

  private static final Logger LOG = Logger.get(AppleDeviceController.class);

  private final ProcessExecutor processExecutor;
  private final Path idbPath;

  /** Enum indicating the different kinds of devices possible */
  public enum AppleDeviceKindEnum {
    MOBILE,
    TV,
    WATCH,
  }

  /** Enum for the different ways to launch the simulator */
  public enum LaunchBehavior {
    DO_NOT_WAIT_FOR_DEBUGGER,
    WAIT_FOR_DEBUGGER
  }

  public AppleDeviceController(ProcessExecutor processExecutor, Path idbPath) {
    this.processExecutor = processExecutor;
    this.idbPath = idbPath;
  }

  /** @return the set of all Apple simulators and physical devices available */
  private ImmutableSet<AppleDevice> getDevices() throws IOException, InterruptedException {
    ImmutableList<String> command = ImmutableList.of(idbPath.toString(), "list-targets", "--json");
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder().setCommand(command).build();
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor.Result result =
        processExecutor.launchAndExecute(
            processExecutorParams,
            options,
            /* stdin */ Optional.empty(),
            /* timeOutMs */ Optional.empty(),
            /* timeOutHandler */ Optional.empty());
    if (result.getExitCode() != 0) {
      LOG.error(result.getMessageForUnexpectedResult(command.toString()));
    }
    if (!result.getStdout().isPresent()) {
      LOG.error("No targets were found.");
    }

    // Treating the json returned by idb
    String[] targetsJson = result.getStdout().orElse("").split("\n");

    ImmutableSet.Builder<AppleDevice> targets = ImmutableSet.builder();

    for (String json : targetsJson) {
      targets.add(
          ObjectMappers.READER.readValue(
              ObjectMappers.createParser(json.trim()), ImmutableAppleDevice.class));
    }
    return targets.build();
  }

  /** @return the set of Apple simulators */
  public ImmutableSet<AppleDevice> getSimulators() {
    ImmutableSet<AppleDevice> devices;
    ImmutableSet.Builder<AppleDevice> simulators = ImmutableSet.builder();
    try {
      devices = getDevices();
    } catch (IOException | InterruptedException e) {
      e.printStackTrace();
      devices = ImmutableSet.of();
    }

    for (AppleDevice device : devices) {
      if (device.getType().equals("simulator")) simulators.add(device);
    }

    return simulators.build();
  }

  /** @return the set of Apple physical devices */
  public ImmutableSet<AppleDevice> getPhysicalDevices() {
    ImmutableSet<AppleDevice> devices;
    ImmutableSet.Builder<AppleDevice> physicalDevices = ImmutableSet.builder();
    try {
      devices = getDevices();
    } catch (IOException | InterruptedException e) {
      e.printStackTrace();
      devices = ImmutableSet.of();
    }

    for (AppleDevice device : devices) {
      if (device.getType().equals("device")) physicalDevices.add(device);
    }
    return physicalDevices.build();
  }

  /** @return set of udids of the booted devices */
  public ImmutableSet<String> getBootedSimulatorsUdids() {
    ImmutableSet.Builder<String> bootedSimulatorUdids = ImmutableSet.builder();
    ImmutableSet<AppleDevice> allTargets = getSimulators();

    for (AppleDevice target : allTargets) {
      if (target.getState().toLowerCase().equals("booted")
          && target.getType().equals("simulator")) {
        bootedSimulatorUdids.add(target.getUdid());
      }
    }
    return bootedSimulatorUdids.build();
  }

  /**
   * @return the udid of the device that has the determined name. If no device is found with that
   *     name, will return optional empty
   */
  public Optional<String> getUdidFromDeviceName(String name) {
    ImmutableSet<AppleDevice> devices;
    try {
      devices = getDevices();
    } catch (IOException | InterruptedException e) {
      e.printStackTrace();
      return Optional.empty();
    }
    for (AppleDevice device : devices) {
      if (device.getName().equals(name)) {
        return Optional.of(device.getUdid());
      }
    }
    return Optional.empty();
  }

  /**
   * Picks the simulator to be used for testing
   *
   * @return the udid of any booted simulator (iphone) and if there are none returns any simulator.
   *     If there are none, returns optional empty
   */
  public Optional<String> getSimulatorUdidForTest() {
    ImmutableSet<AppleDevice> simulators = getSimulators();
    if (simulators.isEmpty()) {
      return Optional.empty();
    }
    for (AppleDevice simulator : simulators) {
      if (simulator.getState().toLowerCase().equals("booted")) {
        return Optional.of(simulator.getUdid());
      }
    }
    for (AppleDevice simulator : simulators) {
      if (getDeviceKind(simulator) == AppleDeviceKindEnum.MOBILE) {
        return Optional.of(simulator.getUdid());
      }
    }
    return Optional.empty();
  }

  /**
   * Starts up the iOS simulator using idb, which waits until the simulator is completely booted.
   *
   * <p>Call {@link #isSimulatorAvailable(String)} before invoking this method to ensure the
   * simulator is available.
   *
   * @return true if idb was able to boot the simulator, false otherwise
   */
  public boolean bootSimulator(String simulatorUdid) throws IOException, InterruptedException {
    // Check if simulator is available
    if (!isSimulatorAvailable(simulatorUdid)) {
      LOG.warn("Did not find the simulator %s", simulatorUdid);
      return false;
    }

    // Boots simulator
    return bootSimulatorWithUdid(simulatorUdid);
  }

  /**
   * @param simulatorUdid of the simulator is available
   * @return true if the simulator is available, false otherwise
   */
  public boolean isSimulatorAvailable(String simulatorUdid) {
    AppleDeviceController deviceController = new AppleDeviceController(processExecutor, idbPath);
    ImmutableSet<AppleDevice> simulators = deviceController.getSimulators();
    for (AppleDevice simulator : simulators) {
      if (simulator.getUdid().equals(simulatorUdid)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @param simulatorUdid, udid of the simulator to boot
   * @return true if it was possible to boot the simulator, false otherwise
   */
  private boolean bootSimulatorWithUdid(String simulatorUdid)
      throws IOException, InterruptedException {
    ImmutableList<String> command =
        ImmutableList.of(idbPath.toString(), "boot", "--udid", simulatorUdid);
    LOG.debug("Booting iOS simulator %s: %s", simulatorUdid, command);
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder().setCommand(command).build();
    ProcessExecutor.Result result = processExecutor.launchAndExecute(processExecutorParams);
    if (result.getExitCode() != 0) {
      LOG.error(result.getMessageForUnexpectedResult(command.toString()));
      return false;
    }
    return true;
  }

  /**
   * Installs a bundle in a previously-started simulator or physical device.
   *
   * @return true if the bundle was installed, false otherwise.
   */
  public Optional<String> installBundle(String deviceUdid, Path bundlePath)
      throws IOException, InterruptedException {
    ImmutableList<String> command =
        ImmutableList.of(
            idbPath.toString(), "install", "--udid", deviceUdid, bundlePath.toString());
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder().setCommand(command).build();
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor.Result result =
        processExecutor.launchAndExecute(
            processExecutorParams,
            options,
            /* stdin */ Optional.empty(),
            /* timeOutMs */ Optional.empty(),
            /* timeOutHandler */ Optional.empty());
    if (result.getExitCode() != 0) {
      LOG.error(result.getMessageForUnexpectedResult(command.toString()));
      return Optional.empty();
    }
    if (!result.getStdout().isPresent()) {
      LOG.error("Could not get the bundle id of the installed app");
      return Optional.empty();
    }

    String output[] = result.getStdout().get().split(" ");
    return Optional.of(output[1]);
  }

  /**
   * Brings the desired simulator to the front
   *
   * @return true if it managed to do so, false otherwise
   */
  public boolean bringSimulatorToFront(String simulatorUdid)
      throws IOException, InterruptedException {
    ImmutableList<String> command =
        ImmutableList.of(idbPath.toString(), "focus", "--udid", simulatorUdid);
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder().setCommand(command).build();
    ProcessExecutor.Result result = processExecutor.launchAndExecute(processExecutorParams);
    if (result.getExitCode() != 0) {
      LOG.warn("Could not bring simulators to front");
      LOG.warn(result.getMessageForUnexpectedResult(command.toString()));
      return false;
    }
    return true;
  }

  /**
   * Launches an installed bundle in a device
   *
   * @return true if it was able to launch and false otherwise
   */
  public boolean launchInstalledBundle(String deviceUdid, String bundleID)
      throws IOException, InterruptedException {
    ImmutableList.Builder<String> commandBuilder = ImmutableList.builder();
    commandBuilder.add(idbPath.toString(), "launch", bundleID, "--udid", deviceUdid);
    ImmutableList<String> command = commandBuilder.build();
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder().setCommand(command).build();

    String message =
        String.format(
            "Launching bundle ID %s in simulator %s via command %s", bundleID, deviceUdid, command);
    LOG.debug(message);

    ProcessExecutor.Result result = processExecutor.launchAndExecute(processExecutorParams);
    if (result.getExitCode() != 0) {
      LOG.error(result.getMessageForResult(message));
      return false;
    }
    return true;
  }

  /**
   * Starts the debugserver of the installed app
   *
   * @return the command to be run in lldb
   */
  public Optional<String> startDebugServer(String deviceUdid, String bundleID)
      throws IOException, InterruptedException {
    ImmutableList<String> command =
        ImmutableList.of(
            idbPath.toString(), "debugserver", "start", bundleID, "--udid", deviceUdid);
    LOG.debug("Starting the debug server with the command %s", command);
    ProcessExecutorParams processExecutorParams =
        ProcessExecutorParams.builder().setCommand(command).build();
    Set<ProcessExecutor.Option> options = EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT);
    ProcessExecutor.Result result =
        processExecutor.launchAndExecute(
            processExecutorParams,
            options,
            /* stdin */ Optional.empty(),
            /* timeOutMs */ Optional.empty(),
            /* timeOutHandler */ Optional.empty());
    if (result.getExitCode() != 0) {
      LOG.error("Could not start debug server");
      return Optional.empty();
    }
    if (!result.getStdout().isPresent()) {
      LOG.error("Could not get debug server command from idb");
      return Optional.empty();
    }
    return result.getStdout();
  }

  /**
   * @param device to be analized
   * @return the enum indicating the kind of device it is
   */
  public AppleDeviceKindEnum getDeviceKind(AppleDevice device) {
    if (device.getName().contains("TV")) return AppleDeviceKindEnum.TV;
    if (device.getName().contains("Watch")) return AppleDeviceKindEnum.WATCH;
    return AppleDeviceKindEnum.MOBILE;
  }
}
