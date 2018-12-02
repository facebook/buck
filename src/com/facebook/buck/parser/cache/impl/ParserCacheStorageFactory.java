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

package com.facebook.buck.parser.cache.impl;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.parser.cache.ParserCacheStorage;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.google.common.base.Preconditions;
import java.io.IOException;

/**
 * Factory for creating the appropriate {@link ParserCacheStorage}, based on the {@link
 * AbstractParserCacheConfig}.
 */
public class ParserCacheStorageFactory {
  private static AbstractParserCacheConfig obtainParserCacheConfig(BuckConfig buckConfig) {
    return buckConfig.getView(ParserCacheConfig.class);
  }

  private static ParserCacheStorage createLocalParserStorage(
      AbstractParserCacheConfig parserCacheConfig, ProjectFilesystem filesystem) {
    Preconditions.checkState(parserCacheConfig.isDirParserCacheEnabled());
    return LocalCacheStorage.of(parserCacheConfig, filesystem);
  }

  private static ParserCacheStorage createRemoteManifestParserStorage(
      AbstractParserCacheConfig parserCacheConfig,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier) {
    Preconditions.checkState(parserCacheConfig.isRemoteParserCacheEnabled());
    return RemoteManifestServiceCacheStorage.of(manifestServiceSupplier.get(), parserCacheConfig);
  }

  /**
   * @returns the appropriate {@link ParserCacheStorage} implementation based on the parameters
   *     passed in.
   */
  static ParserCacheStorage createParserCacheStorage(
      BuckConfig buckConfig,
      ProjectFilesystem filesystem,
      ThrowingCloseableMemoizedSupplier<ManifestService, IOException> manifestServiceSupplier) {
    // TODO(buck_team): Generalize this to return a list of parser storages (see TODOs in
    // HybridCacheStorage.java).
    AbstractParserCacheConfig parserCacheConfig = obtainParserCacheConfig(buckConfig);
    Preconditions.checkState(
        parserCacheConfig.isDirParserCacheEnabled()
            || parserCacheConfig.isRemoteParserCacheEnabled());

    if (parserCacheConfig.isDirParserCacheEnabled()
        && parserCacheConfig.isRemoteParserCacheEnabled()) {
      ParserCacheStorage localStorage = createLocalParserStorage(parserCacheConfig, filesystem);
      ParserCacheStorage remoteManifestStorage =
          createRemoteManifestParserStorage(parserCacheConfig, manifestServiceSupplier);

      return HybridCacheStorage.of(localStorage, remoteManifestStorage);
    }

    if (parserCacheConfig.isDirParserCacheEnabled()) {
      return createLocalParserStorage(parserCacheConfig, filesystem);
    }

    return createRemoteManifestParserStorage(parserCacheConfig, manifestServiceSupplier);
  }
}
