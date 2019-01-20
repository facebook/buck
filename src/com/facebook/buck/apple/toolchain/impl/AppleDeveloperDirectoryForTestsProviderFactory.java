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
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryForTestsProvider;
import com.facebook.buck.apple.toolchain.AppleDeveloperDirectoryProvider;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class AppleDeveloperDirectoryForTestsProviderFactory
    implements ToolchainFactory<AppleDeveloperDirectoryForTestsProvider> {

  @Override
  public Optional<AppleDeveloperDirectoryForTestsProvider> createToolchain(
      ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
    AppleConfig appleConfig = context.getBuckConfig().getView(AppleConfig.class);
    Optional<String> xcodeDeveloperDirectory = appleConfig.getXcodeDeveloperDirectoryForTests();
    Path developerDirectoryForTests;
    if (xcodeDeveloperDirectory.isPresent()) {
      Path developerDirectory =
          context
              .getBuckConfig()
              .resolvePathThatMayBeOutsideTheProjectFilesystem(
                  Paths.get(xcodeDeveloperDirectory.get()));
      try {
        developerDirectoryForTests = developerDirectory.toRealPath();
      } catch (IOException e) {
        developerDirectoryForTests = developerDirectory;
      }
    } else {
      developerDirectoryForTests =
          toolchainProvider
              .getByName(
                  AppleDeveloperDirectoryProvider.DEFAULT_NAME,
                  AppleDeveloperDirectoryProvider.class)
              .getAppleDeveloperDirectory();
    }

    return Optional.of(AppleDeveloperDirectoryForTestsProvider.of(developerDirectoryForTests));
  }
}
