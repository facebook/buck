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

package com.facebook.buck.rules;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.event.ArtifactCompressionEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.file.MoreFiles;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.unarchive.ArchiveFormat;
import com.facebook.buck.util.unarchive.ExistingFileMode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;

public class BuildCacheArtifactFetcher {

  private static final Logger LOG = Logger.get(BuildCacheArtifactFetcher.class);

  private final BuildRule rule;
  private final CachingBuildRuleBuilder.BuildRuleScopeManager buildRuleScopeManager;
  private final WeightedListeningExecutorService executorService;
  private final OnOutputsWillChange onOutputsWillChange;
  private final BuckEventBus eventBus;
  private final BuildInfoStoreManager buildInfoStoreManager;
  private final CachingBuildEngine.MetadataStorage metadataStorage;
  private final OnDiskBuildInfo onDiskBuildInfo;

  public BuildCacheArtifactFetcher(
      BuildRule rule,
      CachingBuildRuleBuilder.BuildRuleScopeManager buildRuleScopeManager,
      WeightedListeningExecutorService executorService,
      OnOutputsWillChange onOutputsWillChange,
      BuckEventBus eventBus,
      BuildInfoStoreManager buildInfoStoreManager,
      CachingBuildEngine.MetadataStorage metadataStorage,
      OnDiskBuildInfo onDiskBuildInfo) {
    this.rule = rule;
    this.buildRuleScopeManager = buildRuleScopeManager;
    this.executorService = executorService;
    this.onOutputsWillChange = onOutputsWillChange;
    this.eventBus = eventBus;
    this.buildInfoStoreManager = buildInfoStoreManager;
    this.metadataStorage = metadataStorage;
    this.onDiskBuildInfo = onDiskBuildInfo;
  }

  private Scope buildRuleScope() {
    return buildRuleScopeManager.scope();
  }

  public ListenableFuture<CacheResult>
      tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
          final RuleKey ruleKey,
          final ArtifactCache artifactCache,
          final ProjectFilesystem filesystem) {
    if (!rule.isCacheable()) {
      return Futures.immediateFuture(CacheResult.ignored());
    }

    // Create a temp file whose extension must be ".zip" for Filesystems.newFileSystem() to infer
    // that we are creating a zip-based FileSystem.
    final LazyPath lazyZipPath =
        new LazyPath() {
          @Override
          protected Path create() throws IOException {
            return Files.createTempFile(
                "buck_artifact_" + MoreFiles.sanitize(rule.getBuildTarget().getShortName()),
                ".zip");
          }
        };

    // TODO(mbolin): Change ArtifactCache.fetch() so that it returns a File instead of takes one.
    // Then we could download directly from the remote cache into the on-disk cache and unzip it
    // from there.
    return Futures.transformAsync(
        fetch(artifactCache, ruleKey, lazyZipPath),
        cacheResult -> {
          try (Scope ignored = buildRuleScope()) {
            // Verify that the rule key we used to fetch the artifact is one of the rule keys
            // reported in it's metadata.
            if (cacheResult.getType().isSuccess()) {
              ImmutableSet<RuleKey> ruleKeys =
                  RichStream.from(cacheResult.getMetadata().entrySet())
                      .filter(e -> BuildInfo.RULE_KEY_NAMES.contains(e.getKey()))
                      .map(Map.Entry::getValue)
                      .map(RuleKey::new)
                      .toImmutableSet();
              if (!ruleKeys.contains(ruleKey)) {
                LOG.warn(
                    "%s: rule keys in artifact don't match rule key used to fetch it: %s not in %s",
                    rule.getBuildTarget(), ruleKey, ruleKeys);
              }
            }

            return Futures.immediateFuture(
                unzipArtifactFromCacheResult(ruleKey, lazyZipPath, filesystem, cacheResult));
          }
        });
  }

  public ListenableFuture<CacheResult> fetch(
      ArtifactCache artifactCache, RuleKey ruleKey, LazyPath outputPath) {
    return Futures.transform(
        artifactCache.fetchAsync(ruleKey, outputPath),
        (CacheResult cacheResult) -> {
          try (Scope ignored = buildRuleScope()) {
            if (cacheResult.getType() != CacheResultType.HIT) {
              return cacheResult;
            }
            for (String ruleKeyName : BuildInfo.RULE_KEY_NAMES) {
              if (!cacheResult.getMetadata().containsKey(ruleKeyName)) {
                continue;
              }
              String ruleKeyValue = cacheResult.getMetadata().get(ruleKeyName);
              try {
                verify(ruleKeyValue);
              } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    String.format(
                        "Invalid '%s' rule key in metadata for artifact '%s' returned by cache '%s': '%s'",
                        ruleKeyName, ruleKey, artifactCache.getClass(), ruleKeyValue),
                    e);
              }
            }
            return cacheResult;
          }
        },
        executorService);
  }

  /**
   * Checks that passed rule key value is valid and throws an {@link IllegalArgumentException} if it
   * is not.
   *
   * @param ruleKeyValue rule key to verify.
   */
  @SuppressWarnings("CheckReturnValue")
  private void verify(String ruleKeyValue) {
    HashCode.fromString(ruleKeyValue);
  }

  private CacheResult unzipArtifactFromCacheResult(
      RuleKey ruleKey, LazyPath lazyZipPath, ProjectFilesystem filesystem, CacheResult cacheResult)
      throws IOException {

    // We only unpack artifacts from hits.
    if (!cacheResult.getType().isSuccess()) {
      LOG.debug("Cache miss for '%s' with rulekey '%s'", rule, ruleKey);
      return cacheResult;
    }
    onOutputsWillChange.call();

    Preconditions.checkState(cacheResult.metadata().isPresent());
    Preconditions.checkArgument(cacheResult.getType() == CacheResultType.HIT);
    LOG.debug("Fetched '%s' from cache with rulekey '%s'", rule, ruleKey);

    // It should be fine to get the path straight away, since cache already did it's job.
    Path zipPath = lazyZipPath.getUnchecked();

    // We unzip the file in the root of the project directory.
    // Ideally, the following would work:
    //
    // Path pathToZip = Paths.get(zipPath.getAbsolutePath());
    // FileSystem fs = FileSystems.newFileSystem(pathToZip, /* loader */ null);
    // Path root = Iterables.getOnlyElement(fs.getRootDirectories());
    // MoreFiles.copyRecursively(root, projectRoot);
    //
    // Unfortunately, this does not appear to work, in practice, because MoreFiles fails when trying
    // to resolve a Path for a zip entry against a file Path on disk.
    ArtifactCompressionEvent.Started started =
        ArtifactCompressionEvent.started(
            ArtifactCompressionEvent.Operation.DECOMPRESS, ImmutableSet.of(ruleKey));
    eventBus.post(started);
    try {
      // First, clear out the pre-existing metadata directory.  We have to do this *before*
      // unpacking the zipped artifact, as it includes files that will be stored in the metadata
      // directory.
      BuildInfoStore buildInfoStore =
          buildInfoStoreManager.get(rule.getProjectFilesystem(), metadataStorage);

      try (ZipFile artifact = new ZipFile(zipPath.toFile())) {
        onDiskBuildInfo.validateArtifact(artifact);
      }

      Preconditions.checkState(
          cacheResult.getMetadata().containsKey(BuildInfo.MetadataKey.ORIGIN_BUILD_ID),
          "Cache artifact for rulekey %s is missing metadata %s.",
          ruleKey,
          BuildInfo.MetadataKey.ORIGIN_BUILD_ID);

      ArchiveFormat.ZIP
          .getUnarchiver()
          .extractArchive(
              zipPath.toAbsolutePath(),
              filesystem,
              ExistingFileMode.OVERWRITE_AND_CLEAN_DIRECTORIES);

      // We only delete the ZIP file when it has been unzipped successfully. Otherwise, we leave it
      // around for debugging purposes.
      Files.delete(zipPath);

      // TODO(cjhopman): This should probably record metadata with the buildInfoRecorder, not
      // directly into the buildInfoStore.
      // Also write out the build metadata.
      buildInfoStore.updateMetadata(rule.getBuildTarget(), cacheResult.getMetadata());
    } finally {
      eventBus.post(ArtifactCompressionEvent.finished(started));
    }

    return cacheResult;
  }

  interface OnOutputsWillChange {
    void call() throws IOException;
  }
}
