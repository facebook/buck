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

package com.facebook.buck.core.build.engine.cache.manager;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.ArtifactInfo;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.manifestservice.ManifestService;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.file.Path;

/** Provides multiple implementations for the ManifestRuleKeyService. */
public abstract class ManifestRuleKeyServiceFactory {
  private ManifestRuleKeyServiceFactory() {
    // Non instantiable.
  }

  /** Return an implementation of ManifestRuleKeyService that uses the ArtifactCache/buckcache. */
  public static ManifestRuleKeyService fromArtifactCache(
      final BuildCacheArtifactFetcher buildCacheArtifactFetcher,
      final ArtifactCache artifactCache) {
    return new ManifestRuleKeyService() {
      @Override
      public ListenableFuture<Void> storeManifest(
          RuleKey manifestKey, Path manifestToUpload, long artifactBuildTimeMs) {
        return artifactCache.store(
            ArtifactInfo.builder()
                .addRuleKeys(manifestKey)
                .setManifest(true)
                .setBuildTimeMs(artifactBuildTimeMs)
                .build(),
            BorrowablePath.borrowablePath(manifestToUpload));
      }

      @Override
      public ListenableFuture<CacheResult> fetchManifest(
          RuleKey manifestKey, LazyPath downloadedManifest) {
        return buildCacheArtifactFetcher.fetch(artifactCache, manifestKey, downloadedManifest);
      }
    };
  }

  /** Return an implementation of the ManifestRuleKeyService that uses the ManifestService. */
  public static ManifestRuleKeyService fromManifestService(ManifestService manifestService) {
    return new ManifestRuleKeyServiceImpl(manifestService);
  }
}
