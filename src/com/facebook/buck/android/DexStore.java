/*
 * Copyright 2012-present Facebook, Inc.
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

import com.google.common.base.Preconditions;

/**
 * Specifies how secondary .dex files should be stored in the .apk.
 */
enum DexStore {
  /**
   * Secondary dexes should be compressed using JAR's deflate.
   */
  JAR(".dex.jar"),

  /**
   * Secondary dexes should be stored uncompressed in jars that will be XZ-compressed.
   */
  XZ(".dex.jar.xz"),
  ;

  private final String extension;

  private DexStore(String extension) {
    this.extension = Preconditions.checkNotNull(extension);
  }

  public String getExtension() {
    return extension;
  }
}
