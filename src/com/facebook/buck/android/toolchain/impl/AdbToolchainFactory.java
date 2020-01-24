/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android.toolchain.impl;

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.toolchain.AdbToolchain;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Toolchain for the adb Android tool. */
public class AdbToolchainFactory implements ToolchainFactory<AdbToolchain> {

  private static final Logger log = Logger.get(AdbToolchainFactory.class);

  @Override
  public Optional<AdbToolchain> createToolchain(
      ToolchainProvider toolchainProvider,
      ToolchainCreationContext context,
      TargetConfiguration toolchainTargetConfiguration) {

    AndroidBuckConfig config = new AndroidBuckConfig(context.getBuckConfig(), Platform.detect());
    FileSystem fileSystem = context.getFilesystem().getRootPath().getFileSystem();

    return resolveAdbPath(config, context.getEnvironment(), fileSystem)
        .map(path -> AdbToolchain.of(path));
  }

  private Optional<Path> resolveAdbPath(
      AndroidBuckConfig config, ImmutableMap<String, String> environment, FileSystem fileSystem) {
    // If we have an android.adb override, then always use that.
    if (config.getAdbOverride().isPresent()) {
      return Optional.of(config.getAdbOverride().get());
    }

    // Else, use the specified search order.
    ImmutableList<String> searchOrder = config.getAdbSearchOrder();

    String binaryExtension = Platform.detect() == Platform.WINDOWS ? ".exe" : "";
    String adbBinary = "adb" + binaryExtension;

    for (String searchItem : searchOrder) {
      if (AndroidBuckConfig.SDK_ENTRY_IN_ADB_PATH_SEARCH_ORDER.equals(searchItem)) {
        AndroidSdkDirectoryResolver sdkDirectoryResolver =
            new AndroidSdkDirectoryResolver(fileSystem, environment, config);
        Path sdk = sdkDirectoryResolver.getSdkOrThrow();
        Path adb = sdk.resolve("platform-tools/" + adbBinary).toAbsolutePath();
        if (adb.toFile().exists()) {
          return Optional.of(adb);
        }
      } else if (AndroidBuckConfig.PATH_ENTRY_IN_ADB_PATH_SEARCH_ORDER.equals(searchItem)) {
        String path = environment.get("PATH");
        if (path != null) {
          for (String pathEntry : path.split(File.pathSeparator)) {
            Path entry = Paths.get(pathEntry);
            Path adb = entry.resolve(adbBinary);
            if (adb.toFile().exists()) {
              return Optional.of(adb);
            }
          }
        }
      } else {
        log.warn("Unrecognized search order item for AdbToolchain: %s", searchItem);
      }
    }
    return Optional.empty();
  }
}
