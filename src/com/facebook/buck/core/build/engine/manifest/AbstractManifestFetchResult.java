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

package com.facebook.buck.core.build.engine.manifest;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import java.util.Optional;
import org.immutables.value.Value;

/** Provides a results summary of a manifest-based cache fetch process. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractManifestFetchResult {

  // Return whether results indicate a valid manifest.
  private boolean isManifestValid() {
    return getManifestCacheResult().getType().isSuccess() && !getManifestLoadError().isPresent();
  }

  // Since some value combinations are mutually-exclusive (and would be more properly modeled by
  // tagged unions), do some consistency checks.
  @Value.Check
  void check() {
    Preconditions.checkArgument(
        !getManifestLoadError().isPresent() || getManifestCacheResult().getType().isSuccess(),
        "manifest load error should only be provided if there is a manifest cache hit");
    Preconditions.checkArgument(
        !getDepFileRuleKey().isPresent() || isManifestValid(),
        "dep file rule key should only be provided if there is a valid manifest");
    Preconditions.checkArgument(
        getManifestStats().isPresent() == isManifestValid(),
        "manifest stats should be provided iff there is a valid manifest");
    Preconditions.checkArgument(
        getRuleCacheResult().isPresent() == getDepFileRuleKey().isPresent(),
        "rule cache result should be provided iff there is a dep file rule key");
  }

  /** @return the result of fetching the manifest from cache. */
  abstract CacheResult getManifestCacheResult();

  /** @return the error generated when trying to load the manifest. */
  abstract Optional<String> getManifestLoadError();

  /** @return stats for the fetched manifest. */
  abstract Optional<ManifestStats> getManifestStats();

  /** @return the matching dep file rule key found in the manifest. */
  abstract Optional<RuleKey> getDepFileRuleKey();

  /** @return the result from fetching the rule outputs via the found dep file rule key. */
  abstract Optional<CacheResult> getRuleCacheResult();
}
