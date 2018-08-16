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

package com.facebook.buck.apple.toolchain.impl;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryProvider;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.ProcessExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

public class AppleDeveloperDirectoryProviderFactory
    implements ToolchainFactory<AppleDeveloperDirectoryProvider> {

  private static final Logger LOG = Logger.get(AppleDeveloperDirectoryProviderFactory.class);

  @Override
  public Optional<AppleDeveloperDirectoryProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    Optional<Path> appleDeveloperDir =
        getAppleDeveloperDirectory(context.getBuckConfig(), context.getProcessExecutor());
    return appleDeveloperDir.map(AppleDeveloperDirectoryProvider::of);
  }

  private static Optional<Path> getAppleDeveloperDirectory(
      BuckConfig buckConfig, ProcessExecutor processExecutor) {
    AppleConfig appleConfig = buckConfig.getView(AppleConfig.class);
    Supplier<Optional<Path>> appleDeveloperDirectorySupplier =
        appleConfig.getAppleDeveloperDirectorySupplier(processExecutor);
    Optional<Path> appleDeveloperDirectory = appleDeveloperDirectorySupplier.get();
    if (appleDeveloperDirectory.isPresent() && !Files.isDirectory(appleDeveloperDirectory.get())) {
      LOG.error(
          "Developer directory is set to %s, but is not a directory",
          appleDeveloperDirectory.get());
      return Optional.empty();
    }
    return appleDeveloperDirectory;
  }
}
