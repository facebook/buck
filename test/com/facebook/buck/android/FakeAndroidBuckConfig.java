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
package com.facebook.buck.android;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;

public class FakeAndroidBuckConfig {

  private Map<String, String> androidSection;
  private Map<String, String> ndkSection;

  FakeAndroidBuckConfig() {
    this.androidSection = new HashMap<>();
    this.ndkSection = new HashMap<>();
  }

  public static FakeAndroidBuckConfig builder() {
    return new FakeAndroidBuckConfig();
  }

  public AndroidBuckConfig build() {
    return new AndroidBuckConfig(
        FakeBuckConfig.builder()
            .setSections(
                ImmutableMap.of(
                    "android", ImmutableMap.copyOf(androidSection),
                    "ndk", ImmutableMap.copyOf(ndkSection)))
            .build(),
        Platform.detect());
  }

  public FakeAndroidBuckConfig setSdkPath(String sdkPath) {
    androidSection.put("sdk_path", sdkPath);
    return this;
  }

  public FakeAndroidBuckConfig setBuildToolsVersion(String buildToolsVersion) {
    androidSection.put("build_tools_version", buildToolsVersion);
    return this;
  }

  public FakeAndroidBuckConfig setNdkPath(String ndkPath) {
    ndkSection.put("ndk_path", ndkPath);
    return this;
  }

  public FakeAndroidBuckConfig setNdkRepositoryPath(String ndkRepository) {
    ndkSection.put("ndk_repository_path", ndkRepository);
    return this;
  }

  public FakeAndroidBuckConfig setNdkVersion(String ndkVersion) {
    ndkSection.put("ndk_version", ndkVersion);
    return this;
  }
}
