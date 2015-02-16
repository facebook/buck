/*
 * Copyright 2012-present Facebook, Inc.
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

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.Client;
import com.android.ddmlib.FileListingService;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.RawImage;
import com.android.ddmlib.ScreenRecorderOptions;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.SyncException;
import com.android.ddmlib.SyncService;
import com.android.ddmlib.TimeoutException;
import com.android.ddmlib.log.LogReceiver;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Basic implementation of IDevice for mocking purposes.
 */
public class TestDevice implements IDevice {

  private boolean isEmulator;
  private String name;
  private String serialNumber;
  private DeviceState state;
  private Map<String, String> properties;

  public static TestDevice createEmulator(String serial) {
    TestDevice device = new TestDevice();
    device.setIsEmulator(true);
    device.setSerialNumber(serial);
    device.setName("emulator-" + serial);
    device.setState(DeviceState.ONLINE);
    return device;
  }

  public static TestDevice createRealDevice(String serial) {
    TestDevice device = new TestDevice();
    device.setIsEmulator(false);
    device.setSerialNumber(serial);
    device.setName("device-" + serial);
    device.setState(DeviceState.ONLINE);
    return device;
  }

  public TestDevice() {
    properties = Maps.newHashMap();
  }

  public void setSerialNumber(String serialNumber) {
    this.serialNumber = serialNumber;
  }

  @Override
  public String getSerialNumber() {
    return serialNumber;
  }

  @Override
  public String getAvdName() {
    if (isEmulator()) {
      return name;
    } else {
      return null;
    }
  }

  public void setName(String name) {
    this.name = name;
  }

  @Override
  public String getName() {
    return name;
  }

  public void setIsEmulator(boolean isEmulator) {
    this.isEmulator = isEmulator;
  }

  @Override
  public boolean isEmulator() {
    return isEmulator;
  }

  public void setState(DeviceState state) {
    this.state = state;
  }

  @Override
  public DeviceState getState() {
    return state;
  }

  @Override
  public boolean isOnline() {
    return state == DeviceState.ONLINE;
  }

  @Override
  public boolean isOffline() {
    return state == DeviceState.OFFLINE;
  }

  @Override
  public boolean isBootLoader() {
    return state == DeviceState.BOOTLOADER;
  }

  @Override
  public Map<String, String> getProperties() {
    return Collections.unmodifiableMap(properties);
  }

  @Override
  public int getPropertyCount() {
    return properties.size();
  }

  @Override
  public String getProperty(String s) {
    return properties.get(s);
  }

  @Override
  public boolean arePropertiesSet() {
    return true;
  }

  @Override
  public String getPropertySync(String s) throws TimeoutException,
      AdbCommandRejectedException, ShellCommandUnresponsiveException, IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getPropertyCacheOrSync(String s) throws TimeoutException,
      AdbCommandRejectedException, ShellCommandUnresponsiveException, IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean supportsFeature(Feature feature) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getMountPoint(String s) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean hasClients() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Client[] getClients() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Client getClient(String s) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getClientName(int i) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SyncService getSyncService() throws TimeoutException,
      AdbCommandRejectedException, IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public FileListingService getFileListingService() {
    throw new UnsupportedOperationException();
  }

  @Override
  public RawImage getScreenshot() throws TimeoutException,
      AdbCommandRejectedException, IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void startScreenRecorder(
      String remoteFilePath,
      ScreenRecorderOptions options,
      IShellOutputReceiver receiver)
      throws
      TimeoutException,
      AdbCommandRejectedException,
      IOException,
      ShellCommandUnresponsiveException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void executeShellCommand(String s, IShellOutputReceiver iShellOutputReceiver)
      throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
      IOException {
    throw new UnsupportedOperationException();
  }

  @Deprecated
  @Override
  public void executeShellCommand(String s, IShellOutputReceiver iShellOutputReceiver, int i)
      throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException,
      IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void executeShellCommand(
      String command,
      IShellOutputReceiver receiver,
      long maxTimeToOutputResponse,
      TimeUnit maxTimeUnits)
      throws
      TimeoutException,
      AdbCommandRejectedException,
      ShellCommandUnresponsiveException,
      IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void runEventLogService(LogReceiver logReceiver) throws TimeoutException,
      AdbCommandRejectedException, IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void runLogService(String s, LogReceiver logReceiver) throws TimeoutException,
      AdbCommandRejectedException, IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createForward(int i, int i1) throws TimeoutException,
      AdbCommandRejectedException, IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createForward(int i, String s, DeviceUnixSocketNamespace deviceUnixSocketNamespace)
      throws TimeoutException, AdbCommandRejectedException, IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeForward(int i, int i1) throws TimeoutException, AdbCommandRejectedException,
      IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeForward(int i, String s, DeviceUnixSocketNamespace deviceUnixSocketNamespace)
      throws TimeoutException, AdbCommandRejectedException, IOException {
    throw new UnsupportedOperationException();
  }


  @Override
  public void pushFile(String s, String s1) throws IOException,
      AdbCommandRejectedException, TimeoutException, SyncException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void pullFile(String s, String s1) throws IOException,
      AdbCommandRejectedException, TimeoutException, SyncException {
    throw new UnsupportedOperationException();
  }

  @Override
  public String installPackage(String s, boolean b, String... strings) throws InstallException {
    throw new UnsupportedOperationException();
  }

  @Override
  public String syncPackageToDevice(String s) throws TimeoutException, AdbCommandRejectedException,
      IOException, SyncException {
    throw new UnsupportedOperationException();
  }

  @Override
  public String installRemotePackage(String s, boolean b, String... strings)
      throws InstallException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeRemotePackage(String s) throws InstallException {
    throw new UnsupportedOperationException();
  }

  @Override
  public String uninstallPackage(String s) throws InstallException {
    throw new UnsupportedOperationException();
  }

  @Override
  public void reboot(String s) throws TimeoutException, AdbCommandRejectedException, IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Integer getBatteryLevel() throws TimeoutException, AdbCommandRejectedException,
      IOException, ShellCommandUnresponsiveException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Integer getBatteryLevel(long l) throws TimeoutException, AdbCommandRejectedException,
      IOException, ShellCommandUnresponsiveException {
    throw new UnsupportedOperationException();
  }
}
