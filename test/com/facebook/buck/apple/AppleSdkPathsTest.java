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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.junit.Test;

import java.nio.file.Paths;

/**
 * Unit tests for {@link AppleSdkPaths}.
 */
public class AppleSdkPathsTest {

  @Test
  public void stringWithNoReferencesIsUnchanged() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("Developer"))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    assertThat(
        appleSdkPaths.replaceSourceTreeReferencesFunction().apply("foo"),
        is(equalTo("foo")));
  }

  @Test
  public void stringWithReferenceIsReplaced() throws Exception {
    AppleSdkPaths appleSdkPaths =
        AppleSdkPaths.builder()
            .setDeveloperPath(Paths.get("Developer"))
            .addToolchainPaths(Paths.get("Toolchains/XcodeDefault.xctoolchain"))
            .setPlatformPath(Paths.get("Platforms/iPhoneOS.platform"))
            .setSdkPath(Paths.get("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk"))
            .build();

    assertThat(
        appleSdkPaths.replaceSourceTreeReferencesFunction().apply("$SDKROOT/usr/include/libxml2"),
        is(equalTo("Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS8.0.sdk/usr/include/libxml2")
    ));
  }
}
