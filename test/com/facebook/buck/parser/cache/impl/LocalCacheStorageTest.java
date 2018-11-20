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

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.cache.ParserCacheException;
import com.facebook.buck.parser.cache.json.BuildFileManifestSerializer;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class LocalCacheStorageTest {
  @Rule public ExpectedException expectedException = ExpectedException.none();
  @Rule public TemporaryPaths tempDir = new TemporaryPaths();

  private static final String FOO_BAR_PATH = "Foo" + File.separator + "Bar";

  private ProjectFilesystem filesystem;
  private LocalHandler localHandler;

  private FakeBuckConfig.Builder getBaseBuckConfigBuilder(
      Path location, boolean isParserConfig, boolean enabled) {
    FakeBuckConfig.Builder builder = FakeBuckConfig.builder();
    if (enabled) {
      builder.setSections(
          "[" + ParserCacheConfig.PARSER_CACHE_SECTION_NAME + "]",
          ParserCacheConfig.PARSER_CACHE_LOCAL_LOCATION_NAME + " = " + location.toString(),
          isParserConfig ? "dir_mode = readwrite" : "");
    }

    builder.setFilesystem(filesystem);
    return builder;
  }

  private byte[] serializeBuildFileManifestToBytes(BuildFileManifest buildFileManifest)
      throws ParserCacheException {

    byte[] serializedBuildFileManifest;
    try {
      serializedBuildFileManifest = BuildFileManifestSerializer.serialize(buildFileManifest);
    } catch (IOException e) {
      throw new ParserCacheException(e, "Failed to serialize BuildFileManifgest to bytes.");
    }
    return serializedBuildFileManifest;
  }

  private ParserCacheConfig getParserCacheConfig(boolean enabled, Path location) {
    FakeBuckConfig.Builder builder = getBaseBuckConfigBuilder(location, true, enabled);
    return builder.build().getView(ParserCacheConfig.class);
  }

  private BuckConfig getConfig() {
    FakeBuckConfig.Builder builder =
        getBaseBuckConfigBuilder(filesystem.getPath("foobar"), false, true);
    builder.setSections("[project]", "Z = Z", "Y = Y");
    return builder.build();
  }

  class LocalHandler extends Handler {

    List<LogRecord> messages = new ArrayList<>(10);

    @Override
    public void publish(LogRecord record) {
      messages.add(record);
    }

    @Override
    public void flush() {}

    @Override
    public void close() throws SecurityException {}
  }

  @Before
  public void setUp() {
    // JimFS is not working on Windows with absolute and relative paths properly.
    assumeThat(Platform.detect(), not(Platform.WINDOWS));
    filesystem = FakeProjectFilesystem.createJavaOnlyFilesystem("/");
    localHandler = new LocalHandler();
    Logger.get(LocalCacheStorage.class).addHandler(localHandler);
  }

  @Test
  public void createLocalCacheStorageWithAbsolutePathAndException() throws IOException {
    expectedException.expect(BuckUncheckedExecutionException.class);
    expectedException.expectMessage("NoSuchFileException");
    filesystem.createNewFile(filesystem.getPath("/foo"));
    Path path = filesystem.getPath("/foo/bar");
    LocalCacheStorage.of(getParserCacheConfig(true, path), filesystem);
  }

  @Test
  public void createLocalCacheStorageWithAbsolutePath() {
    Path absPath = filesystem.getBuckPaths().getBuckOut().resolve("/foo/bar").toAbsolutePath();
    LocalCacheStorage.of(getParserCacheConfig(true, absPath), filesystem);
    assertTrue(filesystem.exists(filesystem.getPath("/foo/bar")));
  }

  @Test
  public void createLocalCacheStorageWithRelativePath() {
    String relPath = "foo/bar";
    Path path = filesystem.getPath(relPath);
    LocalCacheStorage.of(getParserCacheConfig(true, path), filesystem);
    assertTrue(filesystem.exists(filesystem.getPath("buck-out/" + relPath)));
  }

  @Test
  public void createLocalCacheStorageWhenCachingDisabled() {
    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage(
        "Invalid state: LocalCacheStorage should not be instantiated if the cache is disabled.");
    LocalCacheStorage.of(
        getParserCacheConfig(false, filesystem.getPath(tempDir.getRoot().toString())), filesystem);
  }

  @Test
  public void createLocalCacheStorageWhenCacheDefaultDirectory() {
    Path emptyPathForDefaultCacheLocation = filesystem.getPath("\"\"");
    LocalCacheStorage.of(getParserCacheConfig(true, emptyPathForDefaultCacheLocation), filesystem);
    assertTrue(filesystem.exists(filesystem.getPath("buck-out")));
  }

  @Test
  public void createLocalCacheWFPDirectoryNonExisting() throws IOException {
    LocalCacheStorage localCacheStorage =
        LocalCacheStorage.of(
            getParserCacheConfig(
                true,
                filesystem.getPath(tempDir.getRoot().toString() + File.separator + FOO_BAR_PATH)),
            filesystem);
    Path buildPath = filesystem.getPath(FOO_BAR_PATH);
    HashCode weakFingerprint = Fingerprinter.getWeakFingerprint(buildPath, getConfig().getConfig());
    HashCode strongFingerprint =
        Fingerprinter.getStrongFingerprint(
            filesystem,
            ImmutableSortedSet.of(),
            new FakeFileHashCache(
                ImmutableMap.of(
                    filesystem.getPath("/foo/bar/FooBar.bzl"), HashCode.fromBytes(new byte[] {1}),
                    filesystem.getPath("/foo/bar/BUCK"), HashCode.fromBytes(new byte[] {2}),
                    filesystem.getPath("/foo/bar/BarFoo.bzl"),
                        HashCode.fromBytes(new byte[] {3}))));
    localCacheStorage.storeBuildFileManifest(
        weakFingerprint, strongFingerprint, new byte[] {0, 1, 2});
    Path localCachePath = tempDir.getRoot().resolve(FOO_BAR_PATH);
    assertNotNull(localCachePath);
    Path weakFingerprintPath =
        localCachePath.resolve(
            Fingerprinter.getWeakFingerprint(buildPath, getConfig().getConfig()).toString());
    assertTrue(checkExistsAfterConvertingToProperFilesystem(buildPath, weakFingerprintPath));
  }

  @Test
  public void createLocalCacheStorageWFPDirectoryExistingAndKeepIt() throws IOException {
    LocalCacheStorage localCacheStorage =
        LocalCacheStorage.of(
            getParserCacheConfig(
                true,
                filesystem.getPath(tempDir.getRoot().toString() + File.separator + FOO_BAR_PATH)),
            filesystem);
    Path buildPath = filesystem.getPath(FOO_BAR_PATH);
    Path localCachePath = tempDir.getRoot().resolve(FOO_BAR_PATH);
    assertNotNull(localCachePath);
    Path wfpPath =
        localCachePath.resolve(
            Fingerprinter.getWeakFingerprint(buildPath, getConfig().getConfig()).toString());
    filesystem.mkdirs(wfpPath);
    Path newFilePath = wfpPath.resolve("foobar");
    filesystem.createNewFile(newFilePath);
    assertTrue(filesystem.exists(newFilePath));
    HashCode weakFingerprint = Fingerprinter.getWeakFingerprint(buildPath, getConfig().getConfig());
    HashCode strongFingerprint =
        Fingerprinter.getStrongFingerprint(
            filesystem,
            ImmutableSortedSet.of(),
            new FakeFileHashCache(
                ImmutableMap.of(
                    filesystem.getPath("/foo/bar/FooBar.bzl"), HashCode.fromBytes(new byte[] {1}),
                    filesystem.getPath("/foo/bar/BUCK"), HashCode.fromBytes(new byte[] {2}),
                    filesystem.getPath("/foo/bar/BarFoo.bzl"),
                        HashCode.fromBytes(new byte[] {3}))));
    localCacheStorage.storeBuildFileManifest(
        weakFingerprint, strongFingerprint, new byte[] {0, 1, 2});
    assertNotNull(localCachePath);
    assertTrue(filesystem.exists(wfpPath));
    assertTrue(filesystem.exists(newFilePath));
  }

  @Test
  public void storeInLocalCacheStorageAndGetFromLocalCacheStorageAndVerifyMatch()
      throws IOException, ParserCacheException {
    LocalCacheStorage localCacheStorage =
        LocalCacheStorage.of(
            getParserCacheConfig(
                true,
                filesystem.getPath(tempDir.getRoot().toString() + File.separator + FOO_BAR_PATH)),
            filesystem);
    Path buildPath = filesystem.getPath(FOO_BAR_PATH);
    Path localCachePath = tempDir.getRoot().resolve(FOO_BAR_PATH);
    assertNotNull(localCachePath);

    GlobSpec globSpec =
        GlobSpec.builder()
            .setExclude(ImmutableList.of("excludeSpec"))
            .setInclude(ImmutableList.of("includeSpec"))
            .setExcludeDirectories(true)
            .build();

    ImmutableList.Builder<GlobSpecWithResult> globSpecBuilder =
        ImmutableList.<GlobSpecWithResult>builder()
            .add(GlobSpecWithResult.of(globSpec, ImmutableSet.of("FooBar.java")));

    globSpec =
        GlobSpec.builder()
            .setExclude(ImmutableList.of("excludeSpec1"))
            .setInclude(ImmutableList.of("includeSpec1"))
            .setExcludeDirectories(false)
            .build();

    globSpecBuilder.add(GlobSpecWithResult.of(globSpec, ImmutableSet.of("FooBar.java")));
    ImmutableList<GlobSpecWithResult> globSpecMap = globSpecBuilder.build();

    ImmutableMap<String, String> configs =
        ImmutableMap.of("confKey1", "confVal1", "confKey2", "confVal2");
    Path include1 = filesystem.createNewFile(filesystem.getPath("Includes1"));
    Path include2 = filesystem.createNewFile(filesystem.getPath("includes2"));
    ImmutableSortedSet<String> includes = ImmutableSortedSet.of("/Includes1", "/includes2");
    ImmutableMap<String, Object> target1Map = ImmutableMap.of("t1K1", "t1V1", "t1K2", "t1V2");
    ImmutableMap<String, Object> target2Map = ImmutableMap.of("t2K1", "t2V1", "t2K2", "t2V2");
    ImmutableMap<String, ImmutableMap<String, Object>> targets =
        ImmutableMap.of("tar1", target1Map, "tar2", target2Map);

    BuildFileManifest buildFileManifest =
        BuildFileManifest.of(
            targets, includes, configs, Optional.of(ImmutableMap.of()), globSpecMap);

    byte[] serializedManifest = BuildFileManifestSerializer.serialize(buildFileManifest);
    String resultString =
        new String(serializedManifest, 0, serializedManifest.length, StandardCharsets.UTF_8);
    assertThat(
        resultString,
        allOf(
            containsString("includeSpec"),
            containsString("excludeSpec"),
            containsString("FooBar.java"),
            containsString("t1K1"),
            containsString("t1V1"),
            containsString("t2K1"),
            containsString("t2V1"),
            containsString("confKey1"),
            containsString("confVal1")));

    // Now deserialize and compare the data.
    BuildFileManifest deserializedManifest =
        BuildFileManifestSerializer.deserialize(serializedManifest);
    assertEquals(buildFileManifest.getTargets(), deserializedManifest.getTargets());
    assertEquals(buildFileManifest.getIncludes(), deserializedManifest.getIncludes());
    assertEquals(buildFileManifest.getConfigs(), deserializedManifest.getConfigs());
    assertEquals(buildFileManifest.getEnv(), deserializedManifest.getEnv());
    assertEquals(buildFileManifest.getGlobManifest(), deserializedManifest.getGlobManifest());

    HashCode weakFingerprinter =
        Fingerprinter.getWeakFingerprint(buildPath, getConfig().getConfig());
    HashCode strongFingerprinter =
        Fingerprinter.getStrongFingerprint(
            filesystem,
            includes,
            new FakeFileHashCache(
                ImmutableMap.of(
                    include1, HashCode.fromBytes(new byte[] {1}),
                    include2, HashCode.fromBytes(new byte[] {2}))));

    // Store in local cache
    localCacheStorage.storeBuildFileManifest(
        weakFingerprinter,
        strongFingerprinter,
        serializeBuildFileManifestToBytes(buildFileManifest));

    Path serializedDataFile =
        localCachePath
            .resolve(weakFingerprinter.toString())
            .resolve(strongFingerprinter.toString());
    assertTrue(checkExistsAfterConvertingToProperFilesystem(buildPath, serializedDataFile));

    // Get from local cache
    BuildFileManifest buildFileManifestResult =
        localCacheStorage.getBuildFileManifest(weakFingerprinter, strongFingerprinter).get();
    assertEquals(buildFileManifest, buildFileManifestResult);
  }

  private boolean checkExistsAfterConvertingToProperFilesystem(
      Path buildPath, Path serializedDataFile) {
    return filesystem.exists(
        filesystem
            .getPathRelativeToProjectRoot(
                buildPath.resolve(filesystem.getPath(serializedDataFile.toString())))
            .get());
  }
}
