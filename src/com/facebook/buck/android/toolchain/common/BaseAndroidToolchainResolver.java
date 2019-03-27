/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.android.toolchain.common;

import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class BaseAndroidToolchainResolver {

  @VisibleForTesting
  public static final String INVALID_DIRECTORY_MESSAGE_TEMPLATE =
      "Configuration '%s' points to an invalid directory '%s'.";

  protected final FileSystem fileSystem;
  protected final ImmutableMap<String, String> environment;

  protected BaseAndroidToolchainResolver(
      FileSystem fileSystem, ImmutableMap<String, String> environment) {
    this.fileSystem = fileSystem;
    this.environment = environment;
  }

  protected Optional<Path> findFirstDirectory(
      ImmutableList<Pair<String, Optional<String>>> possiblePaths) {
    for (Pair<String, Optional<String>> possiblePath : possiblePaths) {
      if (possiblePath.getSecond().isPresent()) {
        Path dirPath = fileSystem.getPath(possiblePath.getSecond().get()).toAbsolutePath();
        if (!Files.isDirectory(dirPath)) {
          throw new RuntimeException(
              String.format(INVALID_DIRECTORY_MESSAGE_TEMPLATE, possiblePath.getFirst(), dirPath));
        }
        return Optional.of(dirPath);
      }
    }
    return Optional.empty();
  }

  protected Pair<String, Optional<String>> getEnvironmentVariable(String key) {
    return new Pair<String, Optional<String>>(key, Optional.ofNullable(environment.get(key)));
  }
}
