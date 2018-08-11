/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.core.resources;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Optional;
import org.junit.Test;

public class AndroidBuckConfigTest {

  public AndroidBuckConfig makeAndroidBuckConfig(ImmutableMap<String, String> ndkSection) {
    return new AndroidBuckConfig(
        FakeBuckConfig.builder().setSections(ImmutableMap.of("ndk", ndkSection)).build(),
        Platform.detect());
  }

  @Test
  public void testNdkAppPlatformForCpuAbi() throws IOException {
    ImmutableMap<String, String> ndkSection =
        new ImmutableMap.Builder<String, String>()
            .put("app_platform_per_cpu_abi", "i386 => foo, arm64 => bar")
            .build();
    AndroidBuckConfig androidBuckConfig = makeAndroidBuckConfig(ndkSection);

    // Make sure we don't have an fallback value.
    assertEquals(androidBuckConfig.getNdkCpuAbiFallbackAppPlatform(), Optional.empty());

    // Make sure we get our ABI values back.
    assertEquals(androidBuckConfig.getNdkAppPlatformForCpuAbi("i386"), Optional.of("foo"));
    assertEquals(androidBuckConfig.getNdkAppPlatformForCpuAbi("arm64"), Optional.of("bar"));

    // Make sure unset ABI values don't return anything, as
    // we didn't set the fallback value.
    assertEquals(androidBuckConfig.getNdkAppPlatformForCpuAbi("fake"), Optional.empty());
  }

  @Test
  public void testNdkAppPlatformUnset() throws IOException {
    ImmutableMap<String, String> ndkSection = new ImmutableMap.Builder<String, String>().build();
    AndroidBuckConfig androidBuckConfig = makeAndroidBuckConfig(ndkSection);

    // Make sure we don't have an fallback value.
    assertEquals(androidBuckConfig.getNdkCpuAbiFallbackAppPlatform(), Optional.empty());

    // Make sure we don't get anything ABI-specific.
    assertEquals(androidBuckConfig.getNdkAppPlatformForCpuAbi("i386"), Optional.empty());
  }

  @Test
  public void testNdkAppPlatformPriority() throws IOException {
    ImmutableMap<String, String> ndkSection =
        new ImmutableMap.Builder<String, String>()
            .put("app_platform", "fallback")
            .put("app_platform_per_cpu_abi", "arm64 => specific")
            .build();
    AndroidBuckConfig androidBuckConfig = makeAndroidBuckConfig(ndkSection);

    // Make sure we have an fallback value.
    assertEquals(androidBuckConfig.getNdkCpuAbiFallbackAppPlatform(), Optional.of("fallback"));

    // Make sure ABI-specific values override the fallback one.
    assertEquals(androidBuckConfig.getNdkAppPlatformForCpuAbi("arm64"), Optional.of("specific"));

    // Make sure we default to fallback.
    assertEquals(androidBuckConfig.getNdkAppPlatformForCpuAbi("fake"), Optional.of("fallback"));
  }
}
