/*
 * Copyright 2016-present Facebook, Inc.
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
package com.facebook.buck.rules;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractCachingBuildEngineBuckConfig implements ConfigView<BuckConfig> {
  /** @return the mode with which to run the build engine. */
  public CachingBuildEngine.BuildMode getBuildEngineMode() {
    return getDelegate()
        .getEnum("build", "engine", CachingBuildEngine.BuildMode.class)
        .orElse(CachingBuildEngine.BuildMode.SHALLOW);
  }

  public CachingBuildEngine.MetadataStorage getBuildMetadataStorage() {
    return getDelegate()
        .getEnum("build", "metadata_storage", CachingBuildEngine.MetadataStorage.class)
        .orElse(CachingBuildEngine.MetadataStorage.FILESYSTEM);
  }

  /** @return the mode with which to run the build engine. */
  public CachingBuildEngine.DepFiles getBuildDepFiles() {
    return getDelegate()
        .getEnum("build", "depfiles", CachingBuildEngine.DepFiles.class)
        .orElse(CachingBuildEngine.DepFiles.ENABLED);
  }

  /** @return the maximum number of entries to support in the depfile cache. */
  public long getBuildMaxDepFileCacheEntries() {
    return getDelegate().getLong("build", "max_depfile_cache_entries").orElse(256L);
  }

  /** @return the maximum size an artifact can be for the build engine to cache it. */
  public Optional<Long> getBuildArtifactCacheSizeLimit() {
    return getDelegate().getLong("build", "artifact_cache_size_limit");
  }

  /** @return the maximum size of files input based rule keys will be willing to hash. */
  public long getBuildInputRuleKeyFileSizeLimit() {
    return getDelegate().getLong("build", "input_rule_key_file_size_limit").orElse(Long.MAX_VALUE);
  }

  public ResourceAwareSchedulingInfo getResourceAwareSchedulingInfo() {
    return ResourceAwareSchedulingInfo.of(
        getDelegate().isResourceAwareSchedulingEnabled(),
        getDelegate().getDefaultResourceAmounts(),
        getDelegate().getResourceAmountsPerRuleType());
  }
}
