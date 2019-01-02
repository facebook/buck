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
import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.artifact_cache.thrift.Manifest;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ManifestRuleKeyServiceFactoryTest {
  @Rule public TemporaryPaths temporaryFolder = new TemporaryPaths();

  private static final RuleKey RULE_KEY = new RuleKey(HashCode.fromLong(42));
  private static final byte[] MANIFEST_DATA = "topspin".getBytes();
  private static long BUILD_TIME_MS = 42;

  @Test
  public void testManifestServiceOnStoreSuccess()
      throws IOException, ExecutionException, InterruptedException {
    ManifestService mockService = EasyMock.createMock(ManifestService.class);
    Capture<Manifest> manifestCapture = EasyMock.newCapture();
    EasyMock.expect(mockService.setManifest(EasyMock.capture(manifestCapture)))
        .andReturn(Futures.immediateFuture(null))
        .once();
    EasyMock.replay(mockService);

    ManifestRuleKeyService service = ManifestRuleKeyServiceFactory.fromManifestService(mockService);
    Path artifactPath = getArtifactPath().getUnchecked();
    Files.write(artifactPath, MANIFEST_DATA);
    service.storeManifest(RULE_KEY, artifactPath, BUILD_TIME_MS).get();

    EasyMock.verify(mockService);
    Manifest manifest = manifestCapture.getValue();
    Assert.assertEquals(1, manifest.getValuesSize());
    Assert.assertEquals(MANIFEST_DATA.length, manifest.getValues().get(0).array().length);
    Assert.assertTrue(Arrays.equals(MANIFEST_DATA, manifest.getValues().get(0).array()));
  }

  @Test
  public void testManifestServiceOnStoreFail() throws InterruptedException, IOException {
    ManifestService mockService = EasyMock.createMock(ManifestService.class);
    IOException exception = new IOException("not good");
    EasyMock.expect(mockService.setManifest(EasyMock.anyObject()))
        .andReturn(Futures.immediateFailedFuture(exception))
        .once();
    EasyMock.replay(mockService);

    ManifestRuleKeyService service = ManifestRuleKeyServiceFactory.fromManifestService(mockService);
    Path artifactPath = getArtifactPath().getUnchecked();
    Files.write(artifactPath, MANIFEST_DATA);
    try {
      service.storeManifest(RULE_KEY, artifactPath, BUILD_TIME_MS).get();
      Assert.fail("Should've thrown.");
    } catch (ExecutionException e) {
      Assert.assertTrue(e.getMessage().contains(exception.getMessage()));
    }

    EasyMock.verify(mockService);
  }

  @Test
  public void testManifestServiceOnFileDoesNotExist() throws InterruptedException, IOException {
    ManifestService mockService = EasyMock.createMock(ManifestService.class);
    EasyMock.replay(mockService);

    ManifestRuleKeyService service = ManifestRuleKeyServiceFactory.fromManifestService(mockService);
    Path artifactPath = getArtifactPath().getUnchecked();
    // Don't materialise the file.

    try {
      service.storeManifest(RULE_KEY, artifactPath, BUILD_TIME_MS).get();
      Assert.fail("Should've thrown.");
    } catch (ExecutionException e) {
      Assert.assertTrue(e.getCause() instanceof IOException);
    }

    EasyMock.verify(mockService);
  }

  @Test
  public void testManifestServiceOnFetchSuccess()
      throws IOException, ExecutionException, InterruptedException {
    ManifestService mockService = EasyMock.createMock(ManifestService.class);
    Manifest expectedManifest = new Manifest();
    expectedManifest.addToValues(ByteBuffer.wrap(MANIFEST_DATA));
    EasyMock.expect(mockService.fetchManifest(EasyMock.anyString()))
        .andReturn(Futures.immediateFuture(expectedManifest))
        .once();
    EasyMock.replay(mockService);

    ManifestRuleKeyService service = ManifestRuleKeyServiceFactory.fromManifestService(mockService);
    LazyPath artifactPath = getArtifactPath();
    CacheResult cacheResult = service.fetchManifest(RULE_KEY, artifactPath).get();

    EasyMock.verify(mockService);

    Assert.assertTrue(Files.exists(artifactPath.getUnchecked()));
    Assert.assertEquals(MANIFEST_DATA.length, Files.size(artifactPath.getUnchecked()));
    Assert.assertEquals(CacheResultType.HIT, cacheResult.getType());
  }

  @Test
  public void testManifestServiceOnFetchMiss()
      throws IOException, ExecutionException, InterruptedException {
    ManifestService mockService = EasyMock.createMock(ManifestService.class);
    Manifest expectedManifest = new Manifest();
    EasyMock.expect(mockService.fetchManifest(EasyMock.anyString()))
        .andReturn(Futures.immediateFuture(expectedManifest))
        .once();
    EasyMock.replay(mockService);

    LazyPath artifactPath = getArtifactPath();
    CacheResult cacheResult =
        ManifestRuleKeyServiceFactory.fromManifestService(mockService)
            .fetchManifest(RULE_KEY, artifactPath)
            .get();

    EasyMock.verify(mockService);

    Assert.assertFalse(Files.exists(artifactPath.getUnchecked()));
    Assert.assertEquals(CacheResultType.MISS, cacheResult.getType());
  }

  @Test
  public void testManifestServiceOnFetchError()
      throws IOException, ExecutionException, InterruptedException {
    ManifestService mockService = EasyMock.createMock(ManifestService.class);
    EasyMock.expect(mockService.fetchManifest(EasyMock.anyString()))
        .andReturn(Futures.immediateFailedFuture(new IOException("things did not go well")))
        .once();
    EasyMock.replay(mockService);

    ManifestRuleKeyService service = ManifestRuleKeyServiceFactory.fromManifestService(mockService);
    LazyPath artifactPath = getArtifactPath();
    CacheResult cacheResult = service.fetchManifest(RULE_KEY, artifactPath).get();

    EasyMock.verify(mockService);
    Assert.assertFalse(Files.exists(artifactPath.getUnchecked()));
    Assert.assertEquals(CacheResultType.ERROR, cacheResult.getType());
  }

  private LazyPath getArtifactPath() throws IOException {
    return LazyPath.ofInstance(temporaryFolder.getRoot().resolve("artifact"));
  }
}
