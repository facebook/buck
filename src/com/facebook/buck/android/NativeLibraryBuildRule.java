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

import com.facebook.buck.rules.BuildRule;

import java.nio.file.Path;

/**
 * {@link BuildRule} that contains various {@code .so} files for Android, organized by target CPU
 * architecture.
 */
public interface NativeLibraryBuildRule {

  /**
   * @return A boolean indicating whether the {@code .so} files in the directory returned by
   *     {@link #getLibraryPath()} should be included in the {@code assets} folder in the APK.
   */
  public boolean isAsset();

  /**
   * Returns the path to the directory containing {@code .so} files organized by target CPU
   * architecture. This often contains subdirectories such as:
   * <ul>
   *   <li><code>armeabi</code>
   *   <li><code>armeabi-v7a</code>
   * </ul>
   * @return A path relative to the project root that should does <em>not</em> include a trailing
   *     slash.
   */
  public Path getLibraryPath();
}
