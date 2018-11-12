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

package com.facebook.buck.parser.cache;

import com.facebook.buck.parser.api.BuildFileManifest;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.util.Optional;

/** This is the main interface for interacting with the cache. */
public interface ParserCacheStorage {

  /**
   * Stores a {@link BuildFileManifest} object to the cache.
   *
   * @param weakFingerprint the weak fingerprint for the {@code buildFileManifest}.
   * @param strongFingerprint the strong fingerprint for the {@code buildFileManifest}.
   * @param serializedBuildFileManifest the serialized {@link BuildFileManifest} to store in the
   *     cache.
   * @throws IOException thrown when there is an error storing the {@link BuildFileManifest}
   */
  void storeBuildFileManifest(
      HashCode weakFingerprint, HashCode strongFingerprint, byte[] serializedBuildFileManifest)
      throws IOException, InterruptedException;

  /**
   * Gets a cached {@link BuildFileManifest} if one is available, based on passed in parameters.
   *
   * @param weakFingerprint the weak fingerprint for the {@code buildFileManifest}.
   * @param strongFingerprint the strong fingerprint for the {@code buildFileManifest}.
   * @return a {@link Optional} of {@link BuildFileManifest} if manifest exists in a storage,
   *     otherwise {@link Optional#empty()}.
   * @throws IOException thrown when there is an error constructing the {@link BuildFileManifest}
   *     from the {@link ParserCacheStorage}.
   */
  Optional<BuildFileManifest> getBuildFileManifest(
      HashCode weakFingerprint, HashCode strongFingerprint)
      throws IOException, InterruptedException;

  /**
   * Deletes the cache entries associated with {@code weakFingerprint} and {@code strongFingerprint}
   *
   * @param weakFingerprint the {@code weakFingerprint} for which to remove the associated cache
   *     records.
   * @param strongFingerprint the {@code strongFingerprint} for which to remove the associated cache
   *     records.
   */
  void deleteCacheEntries(HashCode weakFingerprint, HashCode strongFingerprint)
      throws IOException, InterruptedException;
}
