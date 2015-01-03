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

import com.google.common.base.Optional;

import java.nio.file.Path;


/**
 * Defines an implementation capable of locating Android SDK and NDK directories.
 */
public interface AndroidDirectoryResolver {
  /**
   * @return {@code Optional.absent()} of the Android SDK is not found, Otherwise an
   * {@code Optional<File>} pointing to the Android SDK.
   */
  Optional<Path> findAndroidSdkDirSafe();

  /**
   * @return The location of the Android SDK.  If the Android SDK is not found, an exception should
   * be thrown.
   */
  Path findAndroidSdkDir();

  /**
   * @return {@code Optional.absent()} of the Android NDK is not found, Otherwise an
   * {@code Optional<File>} pointing to the Android NDK.
   */
  Optional<Path> findAndroidNdkDir();

  /**
   * @return The NDK version in use from the directory returned by {@link #findAndroidNdkDir()}.
   */
  Optional<String> getNdkVersion();
}
