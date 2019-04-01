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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.artifact_cache.thrift.Manifest;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.manifestservice.ManifestService;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ImmutableBuildFileManifest;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.cache.ParserCacheException;
import com.facebook.buck.parser.cache.json.BuildFileManifestSerializer;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.facebook.buck.util.ThrowingCloseableMemoizedSupplier;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ParserCacheTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();

  private ProjectFilesystem filesystem;

  /** Operation over a Manifest. */
  static class ManifestServiceImpl implements ManifestService {
    private final Map<String, ArrayList<ByteBuffer>> fingerprints = new HashMap<>();

    /** Appends one more entry to the manifest. Creates a new one if it does not already exist. */
    @Override
    public ListenableFuture<Void> appendToManifest(Manifest manifest) {
      return addToManifestBackingCollection(manifest);
    }

    /**
     * Fetch the current value of the Manifest. An empty list is returned if no value is present.
     */
    @Override
    public ListenableFuture<Manifest> fetchManifest(String manifestKey) {
      Manifest manifest = new Manifest();
      manifest.setKey(manifestKey);

      List<ByteBuffer> storedValues = fingerprints.get(manifestKey);
      if (storedValues == null) {
        storedValues = ImmutableList.of();
      }
      manifest.setValues(storedValues);
      return Futures.immediateFuture(manifest);
    }

    /** Deletes an existing Manifest. */
    @Override
    public ListenableFuture<Void> deleteManifest(String manifestKey) {
      fingerprints.remove(manifestKey);
      return Futures.immediateFuture(null);
    }

    /** Sets the Manifest for key. Overwrites existing one if it already exists. */
    @Override
    public ListenableFuture<Void> setManifest(Manifest manifest) {
      return addToManifestBackingCollection(manifest);
    }

    private ListenableFuture<Void> addToManifestBackingCollection(Manifest manifest) {
      String key = manifest.key;
      ArrayList fingerprintsForKey = fingerprints.get(key);
      if (fingerprintsForKey == null) {
        fingerprintsForKey = new ArrayList();
        fingerprints.put(key, fingerprintsForKey);
      }

      for (ByteBuffer bytes : manifest.values) {
        fingerprintsForKey.add(bytes);
      }

      return Futures.immediateFuture(null);
    }

    @Override
    public void close() throws IOException {}
  }

  private static ThrowingCloseableMemoizedSupplier<ManifestService, IOException>
      getManifestSupplier() {
    return ThrowingCloseableMemoizedSupplier.of(
        () -> new ManifestServiceImpl(), ManifestService::close);
  }

  private BuckConfig getConfig(Path path, String localAccess, String remoteAccess) {
    return FakeBuckConfig.builder()
        .setSections(
            "[parser]",
            "remote_parser_caching_access_mode = " + remoteAccess,
            "dir = " + path.toString(),
            "dir_mode = " + localAccess,
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
  }

  static class FakeParser implements ProjectBuildFileParser {

    private final ProjectFilesystem filesystem;
    private final boolean throwsBuildFileParseException;

    FakeParser(ProjectFilesystem filesystem) {
      this.filesystem = filesystem;
      this.throwsBuildFileParseException = false;
    }

    FakeParser(ProjectFilesystem filesystem, boolean throwsBuildFileParseException) {
      this.filesystem = filesystem;
      this.throwsBuildFileParseException = throwsBuildFileParseException;
    }

    @Override
    public BuildFileManifest getBuildFileManifest(Path buildFile)
        throws BuildFileParseException, InterruptedException, IOException {
      return null;
    }

    @Override
    public void reportProfile() throws IOException {}

    @Override
    public ImmutableSortedSet<String> getIncludedFiles(Path buildFile)
        throws BuildFileParseException, InterruptedException, IOException {
      if (throwsBuildFileParseException) {
        throw BuildFileParseException.createForUnknownParseError("Fake exception!");
      }

      return ImmutableSortedSet.of(
          filesystem.getPath(filesystem.getRootPath().toString(), "Includes1").toString(),
          filesystem.getPath(filesystem.getRootPath().toString(), "Includes2").toString());
    }

    @Override
    public boolean globResultsMatchCurrentState(
        Path buildFile, ImmutableList<GlobSpecWithResult> existingGlobsWithResults)
        throws IOException, InterruptedException {
      return true;
    }

    @Override
    public void close() throws BuildFileParseException, InterruptedException, IOException {}
  }

  @Test
  public void testHybridStorageInstantiatedWhenLocalAndRemoteStoragesEnabled()
      throws IOException, ExecutionException, InterruptedException, ParserCacheException {
    BuckConfig buckConfig = getConfig(filesystem.getPath("foobar"), "readwrite", "readwrite");
    ParserCache parserCache =
        ParserCache.of(
            buckConfig, filesystem, getManifestSupplier(), BuckEventBusForTests.newInstance());
    assertTrue(parserCache.getParserCacheStorage() instanceof HybridCacheStorage);
  }

  @Test
  public void testRemoteStorageInstantiatedWhenRemoteOnlyEnabled()
      throws IOException, ExecutionException, InterruptedException, ParserCacheException {
    BuckConfig buckConfig = getConfig(filesystem.getPath("foobar"), "none", "readwrite");
    ParserCache parserCache =
        ParserCache.of(
            buckConfig, filesystem, getManifestSupplier(), BuckEventBusForTests.newInstance());
    assertTrue(parserCache.getParserCacheStorage() instanceof RemoteManifestServiceCacheStorage);
  }

  @Test
  public void testLocalStorageInstantiatedWhenLocalOnlyEnabled()
      throws IOException, ExecutionException, InterruptedException, ParserCacheException {
    BuckConfig buckConfig = getConfig(filesystem.getPath("foobar"), "readwrite", "none");
    ParserCache parserCache =
        ParserCache.of(
            buckConfig, filesystem, getManifestSupplier(), BuckEventBusForTests.newInstance());
    assertTrue(parserCache.getParserCacheStorage() instanceof LocalCacheStorage);
  }

  @Test
  public void testCacheWhenGetAllIncludesThrowsBuildFileParseException()
      throws IOException, ExecutionException, InterruptedException, ParserCacheException {
    BuckConfig buckConfig = getConfig(filesystem.getPath("foobar"), "none", "readwrite");
    ParserCache parserCache =
        ParserCache.of(
            buckConfig, filesystem, getManifestSupplier(), BuckEventBusForTests.newInstance());
    Path buildPath = filesystem.getPath("Foo/Bar");
    assertFalse(
        parserCache
            .getBuildFileManifest(
                buildPath,
                new FakeParser(filesystem, true),
                HashCode.fromInt(1),
                HashCode.fromInt(2))
            .isPresent());
  }

  @Test
  public void storeInRemoteCacheAndGetFromRemoteCacheAndVerifyMatch()
      throws IOException, ExecutionException, InterruptedException, ParserCacheException {
    BuckConfig buckConfig = getConfig(filesystem.getPath("foobar"), "readwrite", "readwrite");

    Path buildPath = filesystem.getPath("Foo/Bar");

    filesystem.createNewFile(filesystem.getPath("Includes1"));
    filesystem.createNewFile(filesystem.getPath("Includes2"));
    ParserCache parserCache =
        ParserCache.of(
            buckConfig, filesystem, getManifestSupplier(), BuckEventBusForTests.newInstance());
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
    ImmutableSortedSet<String> includes = ImmutableSortedSet.of("/Includes1", "/Includes2");
    ImmutableMap target1 = ImmutableMap.of("t1K1", "t1V1", "t1K2", "t1V2");
    ImmutableMap target2 = ImmutableMap.of("t2K1", "t2V1", "t2K2", "t2V2");
    ImmutableMap targets = ImmutableMap.of("tar1", target1, "tar2", target2);

    BuildFileManifest buildFileManifest =
        ImmutableBuildFileManifest.of(
            targets, includes, configs, Optional.of(ImmutableMap.of()), globSpecs);

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

    // Store in cache
    parserCache.storeBuildFileManifest(
        buildPath, buildFileManifest, HashCode.fromInt(1), HashCode.fromInt(2));

    // Get from local cache
    Optional<BuildFileManifest> cachedBuildFileManifest =
        parserCache.getBuildFileManifest(
            buildPath, new FakeParser(filesystem), HashCode.fromInt(1), HashCode.fromInt(2));
    assertEquals(buildFileManifest, cachedBuildFileManifest.get());
  }
}
