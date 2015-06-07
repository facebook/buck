/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.step.TargetDevice;

import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

public class TargetDeviceCommandLineOptionsTest {
  @Test
  public void shouldReturnAnAbsentOptionalIfNoTargetDeviceCommandLineOptionsSet() {
    TargetDeviceCommandLineOptions options = buildOptions();

    assertFalse(options.getTargetDeviceOptional().isPresent());
  }

  @Test
  public void shouldReturnAnEmulatorIfOnlyEmulatorFlagSet() {
    TargetDeviceCommandLineOptions options = buildOptions("-e");

    TargetDevice device = options.getTargetDeviceOptional().get();

    assertTrue(device.isEmulator());
    assertNull(device.getIdentifier());
  }

  @Test
  public void shouldReturnADeviceIfOnlyDeviceFlagSet() {
    TargetDeviceCommandLineOptions options = buildOptions("-d");

    TargetDevice device = options.getTargetDeviceOptional().get();

    assertFalse(device.isEmulator());
    assertNull(device.getIdentifier());
  }

  @Test
  public void onlySettingTheSerialFlagAssumesTheTargetIsARealDevice() {
    TargetDeviceCommandLineOptions options = buildOptions("-s", "1234");

    TargetDevice device = options.getTargetDeviceOptional().get();

    assertFalse(device.isEmulator());
    assertEquals("1234", device.getIdentifier());
  }

  @Test
  public void serialFlagOverridesEnvironment() {
    TargetDeviceCommandLineOptions options = new TargetDeviceCommandLineOptions("1234");

    TargetDevice device = options.getTargetDeviceOptional().get();

    assertEquals("1234", device.getIdentifier());

    try {
      new CmdLineParser(options).parseArgument("-s", "5678");
    } catch (CmdLineException e) {
      fail("Unable to parse arguments");
    }

    device = options.getTargetDeviceOptional().get();

    assertEquals("5678", device.getIdentifier());
  }

  private TargetDeviceCommandLineOptions buildOptions(String... args) {
    TargetDeviceCommandLineOptions options = new TargetDeviceCommandLineOptions();

    try {
      new CmdLineParser(options).parseArgument(args);
    } catch (CmdLineException e) {
      fail("Unable to parse arguments");
    }

    return options;
  }
}
