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
import com.facebook.buck.artifact_cache.ArtifactUploader;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfo;
import com.facebook.buck.core.build.engine.buildinfo.OnDiskBuildInfo;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.SupportsInputBasedRuleKey;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public class BuildCacheArtifactUploader {
  private final RuleKey defaultKey;
  private final Supplier<Optional<RuleKey>> inputBasedKey;
  private final OnDiskBuildInfo onDiskBuildInfo;
  private final BuildRule rule;
  private final ManifestRuleKeyManager manifestRuleKeyManager;
  private final BuckEventBus eventBus;
  private final ArtifactCache artifactCache;
  private final Optional<Long> artifactCacheSizeLimit;

  public BuildCacheArtifactUploader(
      RuleKey defaultKey,
      Supplier<Optional<RuleKey>> inputBasedKey,
      OnDiskBuildInfo onDiskBuildInfo,
      BuildRule rule,
      ManifestRuleKeyManager manifestRuleKeyManager,
      BuckEventBus eventBus,
      ArtifactCache artifactCache,
      Optional<Long> artifactCacheSizeLimit) {
    this.defaultKey = defaultKey;
    this.inputBasedKey = inputBasedKey;
    this.onDiskBuildInfo = onDiskBuildInfo;
    this.rule = rule;
    this.manifestRuleKeyManager = manifestRuleKeyManager;
    this.eventBus = eventBus;
    this.artifactCache = artifactCache;
    this.artifactCacheSizeLimit = artifactCacheSizeLimit;
  }

  public ListenableFuture<Void> uploadToCache(BuildRuleSuccessType success) throws IOException {
    // Collect up all the rule keys we have index the artifact in the cache with.
    Set<RuleKey> ruleKeys = new HashSet<>();

    // If the rule key has changed (and is not already in the cache), we need to push
    // the artifact to cache using the new key.
    ruleKeys.add(defaultKey);

    // If the input-based rule key has changed, we need to push the artifact to cache
    // using the new key.
    if (SupportsInputBasedRuleKey.isSupported(rule)) {
      Preconditions.checkNotNull(
          inputBasedKey, "input-based key should have been computed already.");
      Optional<RuleKey> calculatedRuleKey = inputBasedKey.get();
      Optional<RuleKey> onDiskRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.INPUT_BASED_RULE_KEY);
      Preconditions.checkState(
          calculatedRuleKey.equals(onDiskRuleKey),
          "%s (%s): %s: invalid on-disk input-based rule key: %s != %s",
          rule.getBuildTarget(),
          rule.getType(),
          success,
          calculatedRuleKey,
          onDiskRuleKey);
      if (calculatedRuleKey.isPresent()) {
        ruleKeys.add(calculatedRuleKey.get());
      }
    }

    // If the manifest-based rule key has changed, we need to push the artifact to cache
    // using the new key.
    if (manifestRuleKeyManager.useManifestCaching()) {
      Optional<RuleKey> onDiskRuleKey =
          onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY);
      if (onDiskRuleKey.isPresent()) {
        ruleKeys.add(onDiskRuleKey.get());
      }
    }

    // Do the actual upload.
    return ArtifactUploader.performUploadToArtifactCache(
        ImmutableSet.copyOf(ruleKeys),
        artifactCache,
        eventBus,
        onDiskBuildInfo.getMetadataForArtifact(),
        onDiskBuildInfo.getPathsForArtifact(),
        rule.getBuildTarget(),
        rule.getProjectFilesystem());
  }

  /** @return whether we should upload the given rules artifacts to cache. */
  public UploadToCacheResultType shouldUploadToCache(
      BuildRuleSuccessType successType, long outputSize) {

    // The success type must allow cache uploading.
    if (!successType.shouldUploadResultingArtifact()) {
      return UploadToCacheResultType.UNCACHEABLE;
    }

    // The cache must be writable.
    if (!artifactCache.getCacheReadMode().isWritable()) {
      return UploadToCacheResultType.CACHEABLE_READONLY_CACHE;
    }

    // If the rule is explicitly marked uncacheable, don't cache it.
    if (!rule.isCacheable()) {
      return UploadToCacheResultType.UNCACHEABLE_RULE;
    }

    // If the rule's outputs are bigger than the preset size limit, don't cache it.
    if (artifactCacheSizeLimit.isPresent() && outputSize > artifactCacheSizeLimit.get()) {
      return UploadToCacheResultType.CACHEABLE_OVER_SIZE_LIMIT;
    }

    return UploadToCacheResultType.CACHEABLE;
  }
}
