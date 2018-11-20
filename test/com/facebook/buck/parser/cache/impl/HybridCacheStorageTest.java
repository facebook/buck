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

package com.facebook.buck.parser.cache.impl;

import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.manifestservice.ManifestServiceConfig;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.cache.ParserCacheStorage;
import com.facebook.buck.parser.cache.json.BuildFileManifestSerializer;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.AbstractFakeClock;
import com.facebook.buck.util.timing.Clock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class HybridCacheStorageTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private ProjectFilesystem filesystem;
  private BuckEventBus eventBus;

  private BuckConfig getConfig(String accessMode, Path path) {
    return FakeBuckConfig.builder()
        .setSections(
            "[parser]",
            "remote_parser_caching_access_mode = " + accessMode,
            "dir = " + path.toString(),
            "dir_mode = readwrite",
            "[project]",
            "Z = " + "Z",
            "Y = " + "Y",
            "[manifestservice]",
            "hybrid_thrift_endpoint=/hybrid_thrift",
            "slb_server_pool=https://buckcache-native.internal.tfbnw.net",
            "slb_timeout_millis=2000",
            "slb_max_acceptable_latency_millis=2000",
            "slb_ping_endpoint=/status.php",
            "slb_health_check_internal_millis=5000")
        .setFilesystem(filesystem)
        .build();
  }

  @Before
  public void setUp() {
    // JimFS is not working on Windows with absolute and relative paths properly.
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/");
    eventBus = BuckEventBusForTests.newInstance();
  }

  ManifestService createManifestService(BuckConfig buckConfig) {
    Clock fakeClock = AbstractFakeClock.doNotCare();
    ManifestServiceConfig config = new ManifestServiceConfig(buckConfig);
    // Make sure we can create the real manifest service.
    config.createManifestService(fakeClock, eventBus, MoreExecutors.newDirectExecutorService());
    // Use a fake service for the tests, though.
    return new FakeManifestService();
  }

  @Test
  public void storeInRemoteCacheAndGetFromRemoteCacheAndVerifyMatch()
      throws IOException, InterruptedException {
    BuckConfig buckConfig = getConfig("readwrite", filesystem.getPath("foobar"));
    ManifestService manifestService = createManifestService(buckConfig);
    ParserCacheConfig parserCacheConfig = buckConfig.getView(ParserCacheConfig.class);
    ParserCacheStorage remoteCache =
        RemoteManifestServiceCacheStorage.of(manifestService, parserCacheConfig);
    ParserCacheStorage localCache = LocalCacheStorage.of(parserCacheConfig, filesystem);
    ParserCacheStorage hybridCache = HybridCacheStorage.of(localCache, remoteCache);
    Path buildPath = filesystem.getPath("Foo/Bar");

    GlobSpec globSpec =
        GlobSpec.builder()
            .setExclude(ImmutableList.of("excludeSpec"))
            .setInclude(ImmutableList.of("includeSpec"))
            .setExcludeDirectories(true)
            .build();
    ImmutableSet<String> globs = ImmutableSet.of("FooBar.java");
    ImmutableList.Builder<GlobSpecWithResult> globSpecsBuilder = ImmutableList.builder();
    globSpecsBuilder.add(GlobSpecWithResult.of(globSpec, globs));

    globSpec =
        GlobSpec.builder()
            .setExclude(ImmutableList.of("excludeSpec1"))
            .setInclude(ImmutableList.of("includeSpec1"))
            .setExcludeDirectories(false)
            .build();
    globs = ImmutableSet.of("BarFoo.java");
    globSpecsBuilder.add(GlobSpecWithResult.of(globSpec, globs));
    ImmutableList<GlobSpecWithResult> globSpecs = globSpecsBuilder.build();

    ImmutableMap configs = ImmutableMap.of("confKey1", "confVal1", "confKey2", "confVal2");
    Path include1 = filesystem.createNewFile(filesystem.getPath("Includes1"));
    Path include2 = filesystem.createNewFile(filesystem.getPath("includes2"));
    ImmutableSortedSet<String> includes = ImmutableSortedSet.of("/Includes1", "/includes2");
    Map<String, Object> target1 = ImmutableMap.of("t1K1", "t1V1", "t1K2", "t1V2");
    Map<String, Object> target2 = ImmutableMap.of("t2K1", "t2V1", "t2K2", "t2V2");
    Map<String, Map<String, Object>> targets = ImmutableMap.of("tar1", target1, "tar2", target2);

    BuildFileManifest buildFileManifest =
        BuildFileManifest.of(targets, includes, configs, Optional.of(ImmutableMap.of()), globSpecs);

    byte[] serializedManifest = BuildFileManifestSerializer.serialize(buildFileManifest);
    String resultString =
        new String(serializedManifest, 0, serializedManifest.length, StandardCharsets.UTF_8);
    assertTrue(resultString.contains("includeSpec"));
    assertTrue(resultString.contains("excludeSpec"));
    assertTrue(resultString.contains("FooBar.java"));
    assertTrue(resultString.contains("t1K1"));
    assertTrue(resultString.contains("t1V1"));
    assertTrue(resultString.contains("t2K1"));
    assertTrue(resultString.contains("t2V1"));
    assertTrue(resultString.contains("confKey1"));
    assertTrue(resultString.contains("confVal1"));

    // Now deserialize and compare the data.
    BuildFileManifest deserializedManifest =
        BuildFileManifestSerializer.deserialize(serializedManifest);
    assertEquals(buildFileManifest.getTargets(), deserializedManifest.getTargets());
    assertEquals(buildFileManifest.getIncludes(), deserializedManifest.getIncludes());
    assertEquals(buildFileManifest.getConfigs(), deserializedManifest.getConfigs());
    assertEquals(buildFileManifest.getGlobManifest(), deserializedManifest.getGlobManifest());

    HashCode weakFingerprint =
        Fingerprinter.getWeakFingerprint(
            buildPath, getConfig("readwrite", filesystem.getPath("foobar")).getConfig());
    HashCode strongFingerprint =
        Fingerprinter.getStrongFingerprint(
            filesystem,
            includes,
            new FakeFileHashCache(
                ImmutableMap.of(
                    include1,
                    HashCode.fromBytes(new byte[] {1}),
                    include2,
                    HashCode.fromBytes(new byte[] {2}))));

    // Store in local cache
    hybridCache.storeBuildFileManifest(weakFingerprint, strongFingerprint, serializedManifest);

    // Get from the hybrid cache
    Optional<BuildFileManifest> cachedBuildFileManifest =
        hybridCache.getBuildFileManifest(weakFingerprint, strongFingerprint);
    assertEquals(buildFileManifest, cachedBuildFileManifest.get());

    // Make sure the data was stored in the local and remote storages.
    // Get from the local cache.
    Optional<BuildFileManifest> localCachedBuildFileManifest =
        ((HybridCacheStorage) hybridCache)
            .localCacheStorage.getBuildFileManifest(weakFingerprint, strongFingerprint);
    assertEquals(buildFileManifest, localCachedBuildFileManifest.get());

    // Get from the remote cache.
    Optional<BuildFileManifest> remoteCachedBuildFileManifest =
        ((HybridCacheStorage) hybridCache)
            .remoteCacheStorage.getBuildFileManifest(weakFingerprint, strongFingerprint);
    assertEquals(buildFileManifest, remoteCachedBuildFileManifest.get());

    hybridCache.deleteCacheEntries(weakFingerprint, strongFingerprint);
  }
}
