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
package com.facebook.buck.util.environment;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.rules.FakeProcessExecutor;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

public class DefaultExecutionEnvironmentTest {
  @Test
  public void testParseNetworksetupOutputForWifi() throws Exception {
    String listAllHardware =
      "Hardware Port: Bluetooth DUN\n" +
      "Device: Bluetooth-Modem\n" +
      "Ethernet Address: N/A\n" +
      "\n" +
      "Hardware Port: Ethernet\n" +
      "Device: en0\n" +
      "Ethernet Address: a1:b2:c3:a4\n" +
      "\n" +
      "Hardware Port: Ethernet Adaptor (en5)\n" +
      "Device: en5\n" +
      "Ethernet Address: 0b:0b:0b:0b:0b:0b\n" +
      "\n" +
      "Hardware Port: FireWire\n" +
      "Device: fw0\n" +
      "Ethernet Address: ff:ff:ff:ff:ff:ff:ff:ff\n" +
      "\n" +
      "Hardware Port: Wi-Fi\n" +
      "Device: en1\n" +
      "Ethernet Address: aa:aa:aa:aa:aa\n" +
      "\n" +
      "Hardware Port: Thunderbolt 1\n" +
      "Device: en4\n" +
      "Ethernet Address: bb:bb:bb:bb:bb\n" +
      "\n" +
      "Hardware Port: Thunderbolt Bridge\n" +
      "Device: bridge0\n" +
      "Ethernet Address: N/A\n" +
      "\n" +
      "VLAN Configurations\n" +
      "===================";
    assertEquals(
        Optional.of("en1"),
        DefaultExecutionEnvironment.parseNetworksetupOutputForWifi(listAllHardware));
    assertEquals(
        Optional.absent(),
        DefaultExecutionEnvironment.parseNetworksetupOutputForWifi("some garbage"));
  }

  @Test
  public void testParseWifiSsid() throws Exception {
    assertEquals(
        Optional.of("duke42"),
        DefaultExecutionEnvironment.parseWifiSsid("Current Wi-Fi Network: duke42\n"));
    assertEquals(
        Optional.absent(),
        DefaultExecutionEnvironment.parseWifiSsid("some garbage"));
  }

  @Test
  public void getUsernameUsesSuppliedEnvironment() {
    String name = "TEST_USER_PLEASE_IGNORE";
    DefaultExecutionEnvironment environment = new DefaultExecutionEnvironment(
        new FakeProcessExecutor(),
        ImmutableMap.of("USER", name),
        System.getProperties());
    assertEquals("Username should match test data.", name, environment.getUsername());
  }
}
