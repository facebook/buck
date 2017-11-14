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

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.function.Supplier;

public class DefaultAndroidLegacyToolchainFactory
    implements ToolchainFactory<AndroidLegacyToolchain> {

  @Override
  public Optional<AndroidLegacyToolchain> createToolchain(
      ToolchainProvider toolchainProvider,
      ImmutableMap<String, String> environment,
      BuckConfig buckConfig,
      ProjectFilesystem filesystem) {

    AndroidBuckConfig androidBuckConfig = new AndroidBuckConfig(buckConfig, Platform.detect());

    AndroidDirectoryResolver androidDirectoryResolver =
        new DefaultAndroidDirectoryResolver(
            filesystem.getRootPath().getFileSystem(), environment, androidBuckConfig);

    Supplier<AndroidPlatformTarget> androidPlatformTargetSupplier =
        new AndroidPlatformTargetSupplier(androidDirectoryResolver, androidBuckConfig);

    return Optional.of(
        new DefaultAndroidLegacyToolchain(androidPlatformTargetSupplier, androidDirectoryResolver));
  }
}
