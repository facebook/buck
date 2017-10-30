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

import com.facebook.buck.io.file.MorePathsForTests;
import java.util.Optional;

public class TestAndroidLegacyToolchainFactory {

  public static AndroidLegacyToolchain create() {
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(MorePathsForTests.rootRelativePath("AndroidSDK")),
            Optional.of(MorePathsForTests.rootRelativePath("AndroidSDK").resolve("build-tools")),
            Optional.of(MorePathsForTests.rootRelativePath("AndroidNDK")),
            Optional.of("15"));
    AndroidPlatformTarget androidPlatformTarget =
        AndroidPlatformTarget.getDefaultPlatformTarget(
            androidDirectoryResolver, Optional.empty(), Optional.empty());

    return new DefaultAndroidLegacyToolchain(() -> androidPlatformTarget, androidDirectoryResolver);
  }

  public static AndroidLegacyToolchain create(AndroidPlatformTarget androidPlatformTarget) {
    AndroidDirectoryResolver androidDirectoryResolver =
        new FakeAndroidDirectoryResolver(
            Optional.of(MorePathsForTests.rootRelativePath("AndroidSDK")),
            Optional.of(MorePathsForTests.rootRelativePath("AndroidSDK").resolve("build-tools")),
            Optional.of(MorePathsForTests.rootRelativePath("AndroidNDK")),
            Optional.of("15"));

    return new DefaultAndroidLegacyToolchain(() -> androidPlatformTarget, androidDirectoryResolver);
  }
}
