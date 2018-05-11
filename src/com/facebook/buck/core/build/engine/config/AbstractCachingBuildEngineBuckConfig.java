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
package com.facebook.buck.core.build.engine.config;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.config.ConfigView;
import com.facebook.buck.config.resources.ResourcesConfig;
import com.facebook.buck.core.build.engine.type.BuildType;
import com.facebook.buck.core.build.engine.type.DepFiles;
import com.facebook.buck.core.build.engine.type.MetadataStorage;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractCachingBuildEngineBuckConfig implements ConfigView<BuckConfig> {
  /** @return the mode with which to run the build engine. */
  public BuildType getBuildEngineMode() {
    return getDelegate().getEnum("build", "engine", BuildType.class).orElse(BuildType.SHALLOW);
  }

  public MetadataStorage getBuildMetadataStorage() {
    return getDelegate()
        .getEnum("build", "metadata_storage", MetadataStorage.class)
        .orElse(MetadataStorage.FILESYSTEM);
  }

  /** @return the mode with which to run the build engine. */
  public DepFiles getBuildDepFiles() {
    return getDelegate().getEnum("build", "depfiles", DepFiles.class).orElse(DepFiles.CACHE);
  }

  /**
   * @return whether to log to console build rule failures as they happen, including rule name and
   *     error text. If false, then depending on keepGoing/verbosity settings, failures may not
   *     appear in the console at all, may only appear at the end of the build, or may be missing
   *     important details (e.g. name of rule is logged, but no error message, or vice-versa).
   */
  public boolean getConsoleLogBuildRuleFailuresInline() {
    return getDelegate()
        .getBoolean("build", "console_log_build_rule_failures_inline")
        .orElse(false);
  }

  /** @return the maximum number of entries to support in the depfile cache. */
  public long getBuildMaxDepFileCacheEntries() {
    return getDelegate().getLong("build", "max_depfile_cache_entries").orElse(256L);
  }

  /** @return the maximum size an artifact can be for the build engine to cache it. */
  public Optional<Long> getBuildArtifactCacheSizeLimit() {
    return getDelegate().getLong("build", "artifact_cache_size_limit");
  }

  public ResourceAwareSchedulingInfo getResourceAwareSchedulingInfo() {
    ResourcesConfig resourcesConfig = getDelegate().getView(ResourcesConfig.class);
    return ResourceAwareSchedulingInfo.of(
        resourcesConfig.isResourceAwareSchedulingEnabled(),
        resourcesConfig.getDefaultResourceAmounts(),
        resourcesConfig.getResourceAmountsPerRuleType());
  }
}
