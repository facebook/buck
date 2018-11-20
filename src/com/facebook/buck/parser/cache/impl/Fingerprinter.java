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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * This class implements functionality to get the weak fingerprint and strong fingerprint hashes for
 * caching parser result - {@link com.facebook.buck.parser.api.BuildFileManifest}.
 *
 * <p>Note: The Fingerprinter object provides fingerprints for the two-level cache used to identify
 * the relevant {@link com.facebook.buck.parser.api.BuildFileManifest} objects in the cache. The
 * first level cache is provided by the so called weak fingerprint. The weak fingerprint encodes
 * some common configuration/environments used by the build file parser, and the name of the file
 * that is associated with the cell currently being built. The weak fingerprint maps to a set of
 * strong fingerprints that are relevant. Examples of entries that are currently included into a
 * weak fingerprint are the buck configuration environment, the name of the build file associated
 * with the cell object being built, platform and architecture for which the build is performed.
 *
 * <p>The strong fingerprint is used to narrow a specific version of the pre-parsed serialized
 * objects - it adds another level of lookup rules that point to a specific match of the current
 * build files states, from a set that use the same weak fingerprints. Currently the strong
 * fingerprint includes the content hashes of all the loaded build files during the parsing of the
 * build spec of interest.
 *
 * <p>Splitting the lookup into two phases reduces the data that needs to be transferred locally
 * since only the entries that match the weak and strong fingerprint could be relevant. More
 * importantly, it reduces the amount of glob operations that need to be performed to validate the
 * use of the cached {@link com.facebook.buck.parser.api.BuildFileManifest} - only the globs for the
 * potential match, both weak and strong fingerprints are performed.
 */
public final class Fingerprinter {
  private Fingerprinter() {}

  /**
   * Gets the weak fingerprint for this configuration.
   *
   * @param buildFile the path to the BUCK file build spec of interest.
   * @param config the {@link Config} object to calculate the weak fingerprint for
   * @return a weak fingerprint - {@link com.google.common.hash.HashCode} that represent a unique
   *     hash value.
   */
  public static HashCode getWeakFingerprint(Path buildFile, Config config) {
    Hasher hasher = Hashing.sha256().newHasher();
    return hasher
        .putString(buildFile.toString(), StandardCharsets.UTF_8)
        .putBytes(config.getOrderIndependentHashCode().asBytes())
        .putString(Platform.detect().name(), StandardCharsets.UTF_8)
        .putString(Architecture.detect().name(), StandardCharsets.UTF_8)
        .hash();
  }

  /**
   * Calculates a strong fingerprint.
   *
   * @param fs the {@link ProjectFilesystem} that we use to calculate the strong fingerprint.
   * @param includes the list of included build files for which we calculate the strong fingerprint.
   * @param fileHashCache the {@link FileHashCache} object to use to get the content hash of the
   *     loaded files.
   * @return a strong fingerprint - {@link HashCode} that represent a unique hash value.
   * @throws IOException can throw if there is a problem getting the content of the main BUCK build
   *     spec or an included file.
   */
  public static HashCode getStrongFingerprint(
      ProjectFilesystem fs, ImmutableSortedSet<String> includes, FileHashCache fileHashCache)
      throws IOException {
    Hasher hasher = Hashing.sha256().newHasher();

    for (String value : includes) {
      Path value_path = fs.getPath(value);
      hasher.putString(fs.relativize(value_path).toString(), StandardCharsets.UTF_8);
      hasher.putBytes(fileHashCache.get(value_path).asBytes());
    }

    return hasher.hash();
  }
}
