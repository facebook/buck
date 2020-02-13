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

package com.facebook.buck.parser.cache.impl;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.parser.cache.ParserCacheStorage;
import com.google.common.base.Preconditions;

/**
 * Factory for creating the appropriate {@link ParserCacheStorage}, based on the {@link
 * ParserCacheConfig}.
 */
public class ParserCacheStorageFactory {
  private static ParserCacheConfig obtainParserCacheConfig(BuckConfig buckConfig) {
    return buckConfig.getView(ParserCacheConfig.class);
  }

  private static ParserCacheStorage createLocalParserStorage(
      ParserCacheConfig parserCacheConfig, ProjectFilesystem filesystem) {
    Preconditions.checkState(parserCacheConfig.isDirParserCacheEnabled());
    return LocalCacheStorage.of(parserCacheConfig, filesystem);
  }

  /**
   * @returns the appropriate {@link ParserCacheStorage} implementation based on the parameters
   *     passed in.
   */
  static ParserCacheStorage createParserCacheStorage(
      BuckConfig buckConfig, ProjectFilesystem filesystem) {
    // TODO(buck_team): Generalize this to return a list of parser storages (see TODOs in
    // HybridCacheStorage.java).
    ParserCacheConfig parserCacheConfig = obtainParserCacheConfig(buckConfig);
    Preconditions.checkState(parserCacheConfig.isDirParserCacheEnabled());

    return createLocalParserStorage(parserCacheConfig, filesystem);
  }
}
