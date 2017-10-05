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

package com.facebook.buck.android.toolchain.impl;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.DefaultAndroidDirectoryResolver;
import com.facebook.buck.android.toolchain.AndroidNdk;
import com.facebook.buck.android.toolchain.AndroidSdk;
import com.facebook.buck.android.toolchain.AndroidToolchain;
import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

public class DefaultAndroidToolchainFactory implements ToolchainFactory<AndroidToolchain> {
  @Override
  public Optional<AndroidToolchain> createToolchain(
      ImmutableMap<String, String> environment,
      BuckConfig buckConfig,
      ProjectFilesystem filesystem) {
    AndroidBuckConfig androidBuckConfig = new AndroidBuckConfig(buckConfig, Platform.detect());

    AndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            filesystem.getRootPath().getFileSystem(),
            environment,
            androidBuckConfig.getBuildToolsVersion(),
            androidBuckConfig.getNdkVersion());
    return createToolchain(androidBuckConfig, androidDirectoryResolver);
  }

  public Optional<AndroidToolchain> createToolchain(
      AndroidBuckConfig androidBuckConfig, AndroidDirectoryResolver androidDirectoryResolver) {
    if (!androidDirectoryResolver.getSdkOrAbsent().isPresent()) {
      return Optional.empty();
    }

    AndroidSdk androidSdk = new DefaultAndroidSdk(androidDirectoryResolver);
    Optional<AndroidNdk> androidNdk;

    if (androidDirectoryResolver.getNdkOrAbsent().isPresent()) {
      androidNdk = Optional.of(new DefaultAndroidNdk(androidBuckConfig, androidDirectoryResolver));
    } else {
      androidNdk = Optional.empty();
    }

    return Optional.of(new DefaultAndroidToolchain(androidSdk, androidNdk));
  }
}
