/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ImmutableProcessExecutorParams;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class AppleConfigTest {

  @Test
  public void getUnspecifiedAppleDeveloperDirectorySupplier() {
    FakeBuckConfig buckConfig = new FakeBuckConfig();
    AppleConfig config = new AppleConfig(buckConfig);
    assertNotNull(config.getAppleDeveloperDirectorySupplier(new FakeProcessExecutor()));
  }

  @Test
  public void getSpecifiedAppleDeveloperDirectorySupplier() {
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of("apple",
            ImmutableMap.of("xcode_developer_dir", "/path/to/somewhere")));
    AppleConfig config = new AppleConfig(buckConfig);
    Supplier<Path> supplier = config.getAppleDeveloperDirectorySupplier(new FakeProcessExecutor());
    assertNotNull(supplier);
    assertEquals(Paths.get("/path/to/somewhere"), supplier.get());
  }

  @Test
  public void getXcodeSelectDetectedAppleDeveloperDirectorySupplier() {
    FakeBuckConfig buckConfig = new FakeBuckConfig();
    AppleConfig config = new AppleConfig(buckConfig);
    ProcessExecutorParams xcodeSelectParams =
        ImmutableProcessExecutorParams.builder()
            .setCommand(ImmutableList.of("xcode-select", "--print-path"))
            .build();
    FakeProcess fakeXcodeSelect = new FakeProcess(0, "/path/to/another/place", "");
    FakeProcessExecutor processExecutor = new FakeProcessExecutor(
        ImmutableMap.of(xcodeSelectParams, fakeXcodeSelect));
    Supplier<Path> supplier = config.getAppleDeveloperDirectorySupplier(processExecutor);
    assertNotNull(supplier);
    assertEquals(Paths.get("/path/to/another/place"), supplier.get());
  }
}
