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

/** Provides configuration options used for working with APKs. */
public class ApkConfig {

  // ApkBuilder is using the most aggressive compression level by default.
  private static final int DEFAULT_COMPRESSION_LEVEL = 9;

  private static final String SECTION_NAME = "apk";

  private final BuckConfig delegate;

  public ApkConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * @return The default compression value used when producing an APK.
   * @see java.util.zip.ZipOutputStream#setLevel(int)
   */
  public int getCompressionLevel() {
    return delegate.getInteger(SECTION_NAME, "compression_level").orElse(DEFAULT_COMPRESSION_LEVEL);
  }
}
