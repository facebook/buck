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

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.LazyPath;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.file.Path;

/** Service in charge of persisting (fetch/store) the manifest for ManifestRuleKeys. */
public interface ManifestRuleKeyService {
  /** Writes the manifest used the ManifestRuleKey into some persistent storage. */
  ListenableFuture<Void> storeManifest(
      RuleKey manifestKey, Path manifestToUpload, long artifactBuildTimeMs);

  /** Reads the manifest used the ManifestRuleKey from some persistent storage. */
  ListenableFuture<CacheResult> fetchManifest(RuleKey manifestKey, LazyPath downloadedManifest);
}
