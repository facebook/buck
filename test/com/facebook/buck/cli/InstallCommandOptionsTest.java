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

import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.base.Joiner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

public class InstallCommandOptionsTest {

  @Rule
  public DebuggableTemporaryFolder temporaryFolder = new DebuggableTemporaryFolder();

  private InstallCommand getCommand(String... args) throws CmdLineException {
    InstallCommand command = new InstallCommand();
    new AdditionalOptionsCmdLineParser(command).parseArgument(args);
    return command;
  }

  private AdbOptions getAdbOptions(
      boolean multiInstallModeConfig,
      String...args) throws CmdLineException, IOException {
    Reader reader = new StringReader(Joiner.on('\n').join(
        "[adb]",
        "multi_install_mode = " + multiInstallModeConfig));
    BuckConfig config = BuckConfigTestUtils.createWithDefaultFilesystem(
        temporaryFolder,
        reader);
    return getCommand(args).adbOptions(config);
  }

  private TargetDeviceOptions getTargetDeviceOptions(String... args) throws CmdLineException {
    return getCommand(args).targetDeviceOptions();
  }

  @Test
  public void testInstallCommandOptionsRun() throws CmdLineException {
    InstallCommand command = getCommand(
        InstallCommand.RUN_SHORT_ARG, "katana",
        VerbosityParser.VERBOSE_SHORT_ARG, "10");
    assertTrue(command.shouldStartActivity());
    assertNull(command.getActivityToStart());
  }

  @Test
  public void testInstallCommandOptionsRunAndActivity() throws CmdLineException {
    InstallCommand command = getCommand(
        InstallCommand.RUN_SHORT_ARG,
        VerbosityParser.VERBOSE_SHORT_ARG, "10",
        "wakizashi",
        InstallCommand.ACTIVITY_SHORT_ARG, "com.facebook.katana.LoginActivity");
    assertTrue(command.shouldStartActivity());
    assertEquals("com.facebook.katana.LoginActivity", command.getActivityToStart());
  }

  @Test
  public void testInstallCommandOptionsActivity() throws CmdLineException {
    InstallCommand command = getCommand(
        "katana",
        InstallCommand.ACTIVITY_SHORT_ARG, ".LoginActivity");
    assertTrue(command.shouldStartActivity());
    assertEquals(".LoginActivity", command.getActivityToStart());
  }

  @Test
  public void testInstallCommandOptionsNone() throws CmdLineException {
    InstallCommand command = getCommand(
        VerbosityParser.VERBOSE_SHORT_ARG, "10",
        "katana");
    assertFalse(command.shouldStartActivity());
    assertNull(command.getActivityToStart());
  }

  @Test
  public void testInstallCommandOptionsEmulatorMode() throws CmdLineException {
    // Short form.
    TargetDeviceOptions options =
        getTargetDeviceOptions(TargetDeviceCommandLineOptions.EMULATOR_MODE_SHORT_ARG);
    assertTrue(options.isEmulatorsOnlyModeEnabled());

    // Long form.
    options = getTargetDeviceOptions(TargetDeviceCommandLineOptions.EMULATOR_MODE_LONG_ARG);
    assertTrue(options.isEmulatorsOnlyModeEnabled());

    // Is off by default.
    options = getTargetDeviceOptions();
    assertFalse(options.isEmulatorsOnlyModeEnabled());
  }

  @Test
  public void testInstallCommandOptionsDeviceMode() throws CmdLineException {
    // Short form.
    TargetDeviceOptions options = getTargetDeviceOptions(
        TargetDeviceCommandLineOptions.DEVICE_MODE_SHORT_ARG);
    assertTrue(options.isRealDevicesOnlyModeEnabled());

    // Long form.
    options = getTargetDeviceOptions(TargetDeviceCommandLineOptions.DEVICE_MODE_LONG_ARG);
    assertTrue(options.isRealDevicesOnlyModeEnabled());

    // Is off by default.
    options = getTargetDeviceOptions();
    assertFalse(options.isRealDevicesOnlyModeEnabled());
  }

  @Test
  public void testInstallCommandOptionsSerial() throws CmdLineException {
    String serial = "some-random-serial-number";
    // Short form.
    TargetDeviceOptions options = getTargetDeviceOptions(
        TargetDeviceCommandLineOptions.SERIAL_NUMBER_SHORT_ARG, serial);
    assertTrue(options.hasSerialNumber());
    assertEquals(serial, options.getSerialNumber());

    // Long form.
    options = getTargetDeviceOptions(TargetDeviceCommandLineOptions.SERIAL_NUMBER_LONG_ARG, serial);
    assertTrue(options.hasSerialNumber());
    assertEquals(serial, options.getSerialNumber());

    // Is off by default.
    options = getTargetDeviceOptions();
    assertFalse(options.hasSerialNumber());
    assertEquals(null, options.getSerialNumber());
  }

  @Test
  public void testInstallCommandOptionsMultiInstallMode() throws CmdLineException, IOException {
    // Short form.
    AdbOptions options = getAdbOptions(false, AdbCommandLineOptions.MULTI_INSTALL_MODE_SHORT_ARG);
    assertTrue(options.isMultiInstallModeEnabled());

    // Long form.
    options = getAdbOptions(false, AdbCommandLineOptions.MULTI_INSTALL_MODE_LONG_ARG);
    assertTrue(options.isMultiInstallModeEnabled());

    // Is off by default.
    options = getAdbOptions(false);
    assertFalse(options.isMultiInstallModeEnabled());
  }

  @Test
  public void testInstallCommandOptionsAdbThreads() throws CmdLineException, IOException {
    // Short form.
    AdbOptions options = getAdbOptions(false, AdbCommandLineOptions.ADB_THREADS_SHORT_ARG, "4");
    assertEquals(4, options.getAdbThreadCount());

    // Long form.
    options = getAdbOptions(false, AdbCommandLineOptions.ADB_THREADS_LONG_ARG, "4");
    assertEquals(4, options.getAdbThreadCount());

    // Is zero by default and overridden when creating the thread pool.
    options = getAdbOptions(false);
    assertEquals(0, options.getAdbThreadCount());
  }

  @Test
  public void testMultiInstallModeFromBuckConfig() throws CmdLineException, IOException {
    AdbOptions options = getAdbOptions(true);
    assertTrue(options.isMultiInstallModeEnabled());

    options = getAdbOptions(true, AdbCommandLineOptions.MULTI_INSTALL_MODE_SHORT_ARG);
    assertTrue(options.isMultiInstallModeEnabled());
  }
}
