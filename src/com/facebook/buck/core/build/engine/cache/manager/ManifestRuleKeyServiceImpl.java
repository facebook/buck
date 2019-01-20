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
import com.facebook.buck.artifact_cache.config.ArtifactCacheMode;
import com.facebook.buck.artifact_cache.thrift.Manifest;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.manifestservice.ManifestService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This service should be used explicitly to store the Manifest from ManifestRuleKeys.
 *
 * <p>Why is it needed? The current buck implementation uses implicitly the buckcache as the
 * persistence storage. This causes problems because the buckcache assumes artifacts are immutable,
 * so if stored once, there's really no point in storing them again. However this is not the case
 * for the Manifest used by ManifestRuleKeys. The big benefits of using the ManifestRuleKeys is that
 * we can append/update the current Manifest with more and more information.
 *
 * <p>Why don't we use the append* call instead of the update* call? This is done for backwards
 * compatibility sake with the older implementation of the buckcache. We can now compare the
 * performance of both implementations side-by-side for correctness and performance.
 *
 * <p>TODO(ruibm): Next steps? Once we prove with data the manifest service performs better and more
 * correctly than the former implementations, we should refactor ManifestRuleKeys so they leverage
 * the ManifestService append* call directly.
 */
/* package private */ class ManifestRuleKeyServiceImpl implements ManifestRuleKeyService {
  private static final Logger LOG = Logger.get(ManifestRuleKeyServiceImpl.class);

  private static final String MANIFEST_SERVICE_SOURCE = "manifest_service";

  private final ManifestService manifestService;

  public ManifestRuleKeyServiceImpl(ManifestService manifestService) {
    this.manifestService = manifestService;
  }

  @Override
  public ListenableFuture<Void> storeManifest(
      RuleKey manifestKey, Path manifestToUpload, long artifactBuildTimeMs) {
    String key = toManifestServiceKey(manifestKey);
    Manifest manifest = new Manifest();
    manifest.setKey(key);
    try {
      manifest.addToValues(ByteBuffer.wrap(Files.readAllBytes(manifestToUpload)));
    } catch (IOException e) {
      LOG.error(
          e,
          "Failed to store key [%s] and path [%s] into the ManifestService.",
          key,
          manifestToUpload);
      return Futures.immediateFailedFuture(e);
    }

    return manifestService.setManifest(manifest);
  }

  @Override
  public ListenableFuture<CacheResult> fetchManifest(
      final RuleKey manifestKey, final LazyPath downloadedManifest) {
    String key = toManifestServiceKey(manifestKey);
    final ListenableFuture<Manifest> future = manifestService.fetchManifest(key);
    final ListenableFuture<CacheResult> cacheResultFuture =
        Futures.transform(
            future,
            (manifest) -> writeManifestToFile(manifest, downloadedManifest),
            MoreExecutors.directExecutor());
    return Futures.catching(
        cacheResultFuture,
        IOException.class,
        (exception) -> {
          String msg = String.format("Failed to fetch manifest with error [%s].", exception);
          LOG.error(exception, msg);
          return CacheResult.error(MANIFEST_SERVICE_SOURCE, ArtifactCacheMode.unknown, msg);
        },
        MoreExecutors.directExecutor());
  }

  public static String toManifestServiceKey(RuleKey manifestKey) {
    return manifestKey.toString();
  }

  private static CacheResult writeManifestToFile(Manifest manifest, LazyPath file) {
    Path path;
    try {
      path = file.get();
    } catch (IOException e) {
      String msg =
          String.format(
              "Failed to get path from LazyPath=[%s] fetching Manifest=[%s].",
              file, manifest.getKey());
      LOG.error(e, msg);
      return CacheResult.error(MANIFEST_SERVICE_SOURCE, ArtifactCacheMode.thrift_over_http, msg);
    }

    if (manifest.getValuesSize() == 0) {
      return CacheResult.miss();
    }

    if (manifest.getValuesSize() > 1) {
      LOG.warn(
          "ManifestRuleKey entries should only contain one value. Found [%d] values for key [%s].",
          manifest.getValuesSize(), manifest.getKey());
    }

    byte[] data = manifest.getValues().get(0).array();
    try {
      Files.write(path, data);
    } catch (IOException e) {
      String msg =
          String.format("Failed to write down [%d bytes] to file [%s].", data.length, path);
      LOG.error(e, msg);
      return CacheResult.error(MANIFEST_SERVICE_SOURCE, ArtifactCacheMode.thrift_over_http, msg);
    }

    return CacheResult.hit(MANIFEST_SERVICE_SOURCE, ArtifactCacheMode.thrift_over_http);
  }
}
