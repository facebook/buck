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

package com.facebook.buck.core.model.impl;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper to compute target configuration hashes. Used by {@link BuildPaths} to include it in the
 * buck-out dir.
 */
public class TargetConfigurationHasher {

  private static ConcurrentHashMap<String, String> mCache = new ConcurrentHashMap<>();

  private TargetConfigurationHasher() {}

  /**
   * Hashes a target configuration.
   *
   * @param targetConfiguration
   * @return A 128-bit murmur3 hash of the fully qualified name of the configuration target. If
   *     there is no configuration target, a hash of the target configuration class name.
   */
  public static String hash(TargetConfiguration targetConfiguration) {
    Optional<BuildTarget> configurationTarget = targetConfiguration.getConfigurationTarget();
    String key =
        configurationTarget.isPresent()
            ? configurationTarget.get().getFullyQualifiedName()
            : targetConfiguration.getClass().getName();
    return mCache.computeIfAbsent(
        key,
        k -> {
          Hasher hasher = Hashing.murmur3_32().newHasher();
          hasher.putBytes(k.getBytes());
          return hasher.hash().toString();
        });
  }
}
