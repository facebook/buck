/*
 * Copyright 2013-present Facebook, Inc.
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

import java.nio.file.Path;
import java.util.Optional;

/** Defines an implementation capable of locating Android SDK and NDK directories. */
public interface AndroidDirectoryResolver {
  /** @return {@code Optional<Path>} pointing to Android SDK or {@code Optional.empty()}. */
  Optional<Path> getSdkOrAbsent();

  /** @return {@code Path} pointing to Android SDK or throws an exception why SDK was not found. */
  Path getSdkOrThrow();

  /** @return {@code Path} pointing to Android NDK or throws an exception why NDK was not found. */
  Path getNdkOrThrow();

  /**
   * @return {@code Path} pointing to Android SDK build tools or throws an exception why the build
   *     tools were not found.
   */
  Path getBuildToolsOrThrow();

  /** @return {@code Optional<Path>} pointing to Android NDK or {@code Optional.empty()}. */
  Optional<Path> getNdkOrAbsent();

  /**
   * @return {@code Optional<String>} of the NDK version pointed by {@code #getNdkOrAbsent} or
   *     {@code Optional.empty()}.
   */
  Optional<String> getNdkVersion();
}
