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

package com.facebook.buck.apple.xcode.xcspec;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class SDKSettingsTest {
  @Test
  public void parsingXcode5SDKSettingsShouldContainExpectedDefaultCompiler() throws Exception {
    Path plist = TestDataHelper.getTestDataDirectory(this).resolve("Xcode5SDKSettings.plist");

    try (InputStream sdkSettingsPlist = Files.newInputStream(plist)) {
      ImmutableMap.Builder<String, String> defaultPropertiesBuilder = ImmutableMap.builder();
      SDKSettings.parseDefaultPropertiesFromPlist(sdkSettingsPlist, defaultPropertiesBuilder);
      ImmutableMap<String, String> defaultProperties = defaultPropertiesBuilder.build();
      assertEquals(
          "com.apple.compilers.llvm.clang.1_0",
          defaultProperties.get(SDKSettings.DEFAULT_COMPILER_KEY));
    }
  }
}
