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
import com.facebook.buck.testutil.TestConsole;
import com.google.common.base.Supplier;
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
    assertNotNull(config.getAppleDeveloperDirectorySupplier(new TestConsole()));
  }

  @Test
  public void getSpecifiedAppleDeveloperDirectorySupplier() {
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of("apple",
            ImmutableMap.of("xcode_developer_dir", "/path/to/somewhere")));
    AppleConfig config = new AppleConfig(buckConfig);
    Supplier<Path> supplier = config.getAppleDeveloperDirectorySupplier(new TestConsole());
    assertNotNull(supplier);
    assertEquals(Paths.get("/path/to/somewhere"), supplier.get());
  }
}
