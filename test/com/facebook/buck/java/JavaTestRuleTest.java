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

package com.facebook.buck.java;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.step.TargetDevice;
import com.facebook.buck.testutil.MoreAsserts;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

public class JavaTestRuleTest {

  @Test
  public void shouldNotAmendVmArgsIfTargetDeviceIsNotPresent() {
    ImmutableList<String> vmArgs = ImmutableList.of("--one", "--two", "--three");
    JavaTest rule = newRule(vmArgs);

    ImmutableList<String> amended = rule.amendVmArgs(vmArgs, Optional.<TargetDevice>absent());

    MoreAsserts.assertListEquals(vmArgs, amended);
  }

  @Test
  public void shouldAddEmulatorTargetDeviceToVmArgsIfPresent() {
    ImmutableList<String> vmArgs = ImmutableList.of("--one");
    JavaTest rule = newRule(vmArgs);

    TargetDevice device = new TargetDevice(TargetDevice.Type.EMULATOR, null);
    ImmutableList<String> amended = rule.amendVmArgs(vmArgs, Optional.of(device));

    ImmutableList<String> expected = ImmutableList.of("--one", "-Dbuck.device=emulator");
    assertEquals(expected, amended);
  }

  @Test
  public void shouldAddRealTargetDeviceToVmArgsIfPresent() {
    ImmutableList<String> vmArgs = ImmutableList.of("--one");
    JavaTest rule = newRule(vmArgs);

    TargetDevice device = new TargetDevice(TargetDevice.Type.REAL_DEVICE, null);
    ImmutableList<String> amended = rule.amendVmArgs(vmArgs, Optional.of(device));

    ImmutableList<String> expected = ImmutableList.of("--one", "-Dbuck.device=device");
    assertEquals(expected, amended);
  }

  @Test
  public void shouldAddDeviceSerialIdToVmArgsIfPresent() {
    ImmutableList<String> vmArgs = ImmutableList.of("--one");
    JavaTest rule = newRule(vmArgs);

    TargetDevice device = new TargetDevice(TargetDevice.Type.EMULATOR, "123");
    List<String> amended = rule.amendVmArgs(vmArgs, Optional.of(device));

    List<String> expected = ImmutableList.of(
        "--one", "-Dbuck.device=emulator", "-Dbuck.device.id=123");
    assertEquals(expected, amended);
  }

  private JavaTest newRule(ImmutableList<String> vmArgs) {
    return (JavaTest) JavaTestBuilder
        .newJavaTestBuilder(BuildTargetFactory.newInstance("//example:test"))
        .setVmArgs(vmArgs)
        .addSrc(Paths.get("ExampleTest.java"))
        .build(new BuildRuleResolver());
  }

}
