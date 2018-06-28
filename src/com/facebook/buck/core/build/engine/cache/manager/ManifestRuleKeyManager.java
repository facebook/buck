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
import com.facebook.buck.core.build.engine.buildinfo.BuildInfo;
import com.facebook.buck.core.build.engine.manifest.Manifest;
import com.facebook.buck.core.build.engine.manifest.ManifestFetchResult;
import com.facebook.buck.core.build.engine.manifest.ManifestLoadResult;
import com.facebook.buck.core.build.engine.manifest.ManifestStoreResult;
import com.facebook.buck.core.build.engine.type.DepFiles;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.keys.RuleKeyAndInputs;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.concurrent.MoreFutures;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.annotation.Nonnull;

public class ManifestRuleKeyManager {

  private static final Logger LOG = Logger.get(ManifestRuleKeyManager.class);

  private final DepFiles depFiles;
  private final BuildRule rule;
  private final FileHashCache fileHashCache;
  private final long maxDepFileCacheEntries;
  private final SourcePathResolver pathResolver;
  private final RuleKeyFactories ruleKeyFactories;
  private final BuildCacheArtifactFetcher buildCacheArtifactFetcher;
  private final ArtifactCache artifactCache;
  private final Supplier<Optional<RuleKeyAndInputs>> manifestBasedKeySupplier;

  public ManifestRuleKeyManager(
      DepFiles depFiles,
      BuildRule rule,
      FileHashCache fileHashCache,
      long maxDepFileCacheEntries,
      SourcePathResolver pathResolver,
      RuleKeyFactories ruleKeyFactories,
      BuildCacheArtifactFetcher buildCacheArtifactFetcher,
      ArtifactCache artifactCache,
      Supplier<Optional<RuleKeyAndInputs>> manifestBasedKeySupplier) {
    this.depFiles = depFiles;
    this.rule = rule;
    this.fileHashCache = fileHashCache;
    this.maxDepFileCacheEntries = maxDepFileCacheEntries;
    this.pathResolver = pathResolver;
    this.ruleKeyFactories = ruleKeyFactories;
    this.buildCacheArtifactFetcher = buildCacheArtifactFetcher;
    this.artifactCache = artifactCache;
    this.manifestBasedKeySupplier = manifestBasedKeySupplier;
  }

  public boolean useManifestCaching() {
    return depFiles == DepFiles.CACHE
        && rule instanceof SupportsDependencyFileRuleKey
        && rule.isCacheable()
        && ((SupportsDependencyFileRuleKey) rule).useDependencyFileRuleKeys();
  }

  @VisibleForTesting
  protected static Path getManifestPath(BuildRule rule) {
    return BuildInfo.getPathToOtherMetadataDirectory(
            rule.getBuildTarget(), rule.getProjectFilesystem())
        .resolve(BuildInfo.MANIFEST);
  }

  // Update the on-disk manifest with the new dep-file rule key and push it to the cache.
  public ManifestStoreResult updateAndStoreManifest(
      RuleKey key,
      ImmutableSet<SourcePath> inputs,
      RuleKeyAndInputs manifestKey,
      ArtifactCache cache)
      throws IOException {

    Preconditions.checkState(useManifestCaching());

    ManifestStoreResult.Builder resultBuilder = ManifestStoreResult.builder();

    Path manifestPath = getManifestPath(rule);
    Manifest manifest = new Manifest(manifestKey.getRuleKey());
    resultBuilder.setDidCreateNewManifest(true);

    // If we already have a manifest downloaded, use that.
    if (rule.getProjectFilesystem().exists(manifestPath)) {
      ManifestLoadResult existingManifest = loadManifest(manifestKey.getRuleKey());
      existingManifest.getError().ifPresent(resultBuilder::setManifestLoadError);
      if (existingManifest.getManifest().isPresent()
          && existingManifest.getManifest().get().getKey().equals(manifestKey.getRuleKey())) {
        manifest = existingManifest.getManifest().get();
        resultBuilder.setDidCreateNewManifest(false);
      }
    } else {
      // Ensure the path to manifest exist
      rule.getProjectFilesystem().createParentDirs(manifestPath);
    }

    // If the manifest is larger than the max size, just truncate it.  It might be nice to support
    // some sort of LRU management here to avoid evicting everything, but it'll take some care to do
    // this efficiently and it's not clear how much benefit this will give us.
    if (manifest.size() >= maxDepFileCacheEntries) {
      manifest = new Manifest(manifestKey.getRuleKey());
      resultBuilder.setDidCreateNewManifest(true);
    }

    // Update the manifest with the new output rule key.
    manifest.addEntry(fileHashCache, key, pathResolver, manifestKey.getInputs(), inputs);

    // Record the current manifest stats settings now that we've finalized the manifest we're going
    // to store.
    resultBuilder.setManifestStats(manifest.getStats());

    // Serialize the manifest to disk.
    try (OutputStream outputStream =
        rule.getProjectFilesystem().newFileOutputStream(manifestPath)) {
      manifest.serialize(outputStream);
    }

    Path tempFile = Files.createTempFile("buck.", ".manifest");
    // Upload the manifest to the cache.  We stage the manifest into a temp file first since the
    // `ArtifactCache` interface uses raw paths.
    try (InputStream inputStream = rule.getProjectFilesystem().newFileInputStream(manifestPath);
        OutputStream outputStream =
            new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tempFile)))) {
      ByteStreams.copy(inputStream, outputStream);
    }

    // Queue the upload operation and save a future wrapping it.
    resultBuilder.setStoreFuture(
        MoreFutures.addListenableCallback(
            cache.store(
                ArtifactInfo.builder()
                    .addRuleKeys(manifestKey.getRuleKey())
                    .setManifest(true)
                    .build(),
                BorrowablePath.borrowablePath(tempFile)),
            MoreFutures.finallyCallback(
                () -> {
                  try {
                    Files.deleteIfExists(tempFile);
                  } catch (IOException e) {
                    LOG.warn(
                        e,
                        "Error occurred while deleting temporary manifest file for %s",
                        manifestPath);
                  }
                }),
            MoreExecutors.directExecutor()));

    return resultBuilder.build();
  }

  public Optional<RuleKeyAndInputs> calculateManifestKey(BuckEventBus eventBus) throws IOException {
    return ruleKeyFactories.calculateManifestKey((SupportsDependencyFileRuleKey) rule, eventBus);
  }

  public ListenableFuture<CacheResult> fetchManifest(RuleKey key) {
    Preconditions.checkState(useManifestCaching());

    Path path = getManifestPath(rule);

    // Use a temp path to store the downloaded artifact.  We'll rename it into place on success to
    // make the process more atomic.
    LazyPath tempPath =
        new LazyPath() {
          @Override
          protected Path create() throws IOException {
            return Files.createTempFile("buck.", ".manifest");
          }
        };

    return Futures.transformAsync(
        buildCacheArtifactFetcher.fetch(artifactCache, key, tempPath),
        (@Nonnull CacheResult cacheResult) -> {
          if (!cacheResult.getType().isSuccess()) {
            LOG.verbose("%s: cache miss on manifest %s", rule.getBuildTarget(), key);
            return Futures.immediateFuture(cacheResult);
          }

          // Download is successful, so move the manifest into place.
          rule.getProjectFilesystem().createParentDirs(path);
          rule.getProjectFilesystem().deleteFileAtPathIfExists(path);

          Path tempManifestPath = Files.createTempFile("buck.", "MANIFEST");
          ungzip(tempPath.get(), tempManifestPath);
          rule.getProjectFilesystem().move(tempManifestPath, path);

          LOG.verbose("%s: cache hit on manifest %s", rule.getBuildTarget(), key);

          return Futures.immediateFuture(cacheResult);
        });
  }

  private void ungzip(Path source, Path destination) throws IOException {
    try (InputStream inputStream =
            new GZIPInputStream(new BufferedInputStream(Files.newInputStream(source)));
        OutputStream outputStream = rule.getProjectFilesystem().newFileOutputStream(destination)) {
      ByteStreams.copy(inputStream, outputStream);
    }
  }

  public ManifestLoadResult loadManifest(RuleKey key) {
    Preconditions.checkState(useManifestCaching());

    Path path = getManifestPath(rule);

    // Deserialize the manifest.
    Manifest manifest;
    // Keep the file input stream in a separate variable so that it gets closed if the
    // GZIPInputStream constructor throws.
    try (InputStream input = rule.getProjectFilesystem().newFileInputStream(path)) {
      manifest = new Manifest(input);
    } catch (Exception e) {
      LOG.warn(
          e,
          "Failed to deserialize fetched-from-cache manifest for rule %s with key %s",
          rule,
          key);
      return ManifestLoadResult.error("corrupted manifest path");
    }

    return ManifestLoadResult.success(manifest);
  }

  // Fetch an artifact from the cache using manifest-based caching.
  public ListenableFuture<ManifestFetchResult> performManifestBasedCacheFetch(
      RuleKeyAndInputs originalRuleKeyAndInputs) throws IOException {
    Preconditions.checkArgument(useManifestCaching());

    // Explicitly drop the input list from the caller, as holding this in the closure below until
    // the future eventually runs can potentially consume a lot of memory.
    RuleKey manifestRuleKey = originalRuleKeyAndInputs.getRuleKey();
    originalRuleKeyAndInputs = null;

    // Fetch the manifest from the cache.
    return Futures.transformAsync(
        fetchManifest(manifestRuleKey),
        (@Nonnull CacheResult manifestCacheResult) -> {
          ManifestFetchResult.Builder manifestFetchResult = ManifestFetchResult.builder();
          manifestFetchResult.setManifestCacheResult(manifestCacheResult);
          if (!manifestCacheResult.getType().isSuccess()) {
            return Futures.immediateFuture(manifestFetchResult.build());
          }

          // Re-calculate the rule key and the input list.  While we do already have the input list
          // above in `originalRuleKeyAndInputs`, we intentionally don't pass it in and use it here
          // to avoid holding on to significant memory until this future runs.
          RuleKeyAndInputs keyAndInputs =
              manifestBasedKeySupplier.get().orElseThrow(IllegalStateException::new);

          // Load the manifest from disk.
          ManifestLoadResult loadResult = loadManifest(keyAndInputs.getRuleKey());
          if (!loadResult.getManifest().isPresent()) {
            manifestFetchResult.setManifestLoadError(loadResult.getError().get());
            return Futures.immediateFuture(manifestFetchResult.build());
          }
          Manifest manifest = loadResult.getManifest().get();
          Preconditions.checkState(
              manifest.getKey().equals(keyAndInputs.getRuleKey()),
              "%s: found incorrectly keyed manifest: %s != %s",
              rule.getBuildTarget(),
              keyAndInputs.getRuleKey(),
              manifest.getKey());
          manifestFetchResult.setManifestStats(manifest.getStats());

          // Lookup the dep file rule key matching the current state of our inputs.
          Optional<RuleKey> depFileRuleKey =
              manifest.lookup(fileHashCache, pathResolver, keyAndInputs.getInputs());
          if (!depFileRuleKey.isPresent()) {
            return Futures.immediateFuture(manifestFetchResult.build());
          }
          manifestFetchResult.setDepFileRuleKey(depFileRuleKey.get());

          // Fetch the rule outputs from cache using the found dep file rule key.
          return Futures.transform(
              buildCacheArtifactFetcher
                  .tryToFetchArtifactFromBuildCacheAndOverlayOnTopOfProjectFilesystem(
                      depFileRuleKey.get(), artifactCache, rule.getProjectFilesystem()),
              (@Nonnull CacheResult ruleCacheResult) -> {
                manifestFetchResult.setRuleCacheResult(ruleCacheResult);
                return manifestFetchResult.build();
              });
        });
  }
}
