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

package com.facebook.buck.android.toolchain.ndk.impl;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.toolchain.ndk.AndroidNdk;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.ToolchainFactory;
import com.facebook.buck.toolchain.ToolchainInstantiationException;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import java.nio.file.Path;
import java.util.Optional;

public class AndroidNdkFactory implements ToolchainFactory<AndroidNdk> {

  @Override
  public Optional<AndroidNdk> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {

    AndroidBuckConfig androidBuckConfig =
        new AndroidBuckConfig(context.getBuckConfig(), Platform.detect());

    AndroidNdkResolver ndkResolver =
        new AndroidNdkResolver(
            context.getFilesystem().getRootPath().getFileSystem(),
            context.getEnvironment(),
            androidBuckConfig);

    Path ndkRoot;

    try {
      ndkRoot = ndkResolver.getNdkOrThrow();
    } catch (HumanReadableException e) {
      throw new ToolchainInstantiationException(e, e.getHumanReadableErrorMessage());
    }

    return Optional.of(
        AndroidNdk.of(
            detectNdkVersion(androidBuckConfig, ndkResolver),
            ndkRoot,
            context.getExecutableFinder()));
  }

  private String detectNdkVersion(
      AndroidBuckConfig androidBuckConfig, AndroidNdkResolver ndkResolver) {
    Optional<String> ndkVersion =
        androidBuckConfig.getNdkVersion().map(Optional::of).orElseGet(ndkResolver::getNdkVersion);
    if (!ndkVersion.isPresent()) {
      throw new ToolchainInstantiationException("Cannot detect NDK version");
    }
    return ndkVersion.get();
  }
}
