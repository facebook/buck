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
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class LocalCacheTest {
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

  private AbstractParserCacheConfig getParserCacheConfig(boolean enabled, Path location) {
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
    Logger.get(LocalCache.class).addHandler(localHandler);
  }

  @Test
  public void createLocalCacheWithAbsolutePathAndException()
      throws IOException, ParserCacheException {
    expectedException.expect(ParserCacheException.class);
    expectedException.expectMessage("Failed to create local cache directory - /foo/bar");
    filesystem.createNewFile(Paths.get("/foo"));
    LocalCache.newInstance(getParserCacheConfig(true, filesystem.getPath("/foo/bar")), filesystem);
  }

  @Test
  public void createLocalCacheWithAbsolutePath() throws ParserCacheException {
    Path absPath = filesystem.getBuckPaths().getBuckOut().resolve("/foo/bar").toAbsolutePath();
    LocalCache.newInstance(getParserCacheConfig(true, absPath), filesystem);
    List<LogRecord> events = localHandler.messages;
    assertEquals(1, events.size());
    LogRecord event = events.get(0);
    assertThat(event.getMessage(), allOf(containsString("Created parser cache directory: %s.")));
    assertThat(event.getParameters()[0].toString(), allOf(containsString(absPath.toString())));
  }

  @Test
  public void createLocalCacheWithRelativePath() throws ParserCacheException {
    Path path = filesystem.getPath("foo/bar");
    LocalCache.newInstance(getParserCacheConfig(true, path), filesystem);
    List<LogRecord> events = localHandler.messages;
    assertEquals(1, events.size());
    LogRecord event = events.get(0);
    assertThat(event.getMessage(), allOf(containsString("Created parser cache directory: %s.")));
    assertThat(
        event.getParameters()[0].toString(),
        allOf(containsString("buck-out" + File.separator + path.toString())));
  }

  @Test
  public void createLocalCacheWhenCachingDisabled() throws ParserCacheException {
    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage(
        "Invalid state: LocalCache should not be instantiated if the cache is disabled.");
    LocalCache.newInstance(
        getParserCacheConfig(false, filesystem.getPath(tempDir.getRoot().toString())), filesystem);
  }

  @Test
  public void createLocalCacheWhenCacheDefaultDirectory() throws ParserCacheException {
    Path emptyPathForDefaultCacheLocation = filesystem.getPath("\"\"");
    LocalCache.newInstance(
        getParserCacheConfig(true, emptyPathForDefaultCacheLocation), filesystem);
    List<LogRecord> events = localHandler.messages;
    assertEquals(1, events.size());
    LogRecord event = events.get(0);
    assertThat(event.getMessage(), allOf(containsString("Created parser cache directory: %s.")));
    assertThat(event.getParameters()[0].toString(), allOf(containsString("buck-out")));
  }

  @Test
  public void createLocalCacheWFPDirectoryNonExisting() throws IOException, ParserCacheException {
    LocalCache localCache =
        LocalCache.newInstance(
            getParserCacheConfig(
                true,
                filesystem.getPath(tempDir.getRoot().toString() + File.separator + FOO_BAR_PATH)),
            filesystem);
    Path buildPath = filesystem.getPath(FOO_BAR_PATH);
    HashCode weakFingerprint = Fingerprinter.getWeakFingerprint(buildPath, getConfig().getConfig());
    HashCode strongFingerprint = Fingerprinter.getStrongFingerprint(filesystem, ImmutableList.of());
    localCache.storeBuildFileManifest(weakFingerprint, strongFingerprint, null);
    Path localCachePath = tempDir.getRoot().resolve(FOO_BAR_PATH);
    assertNotNull(localCachePath);
    Path weakFingerprintPath =
        localCachePath.resolve(
            Fingerprinter.getWeakFingerprint(buildPath, getConfig().getConfig()).toString());
    assertTrue(checkExistsAfterConvertingToProperFilesystem(buildPath, weakFingerprintPath));
  }

  @Test
  public void createLocalCacheWFPDirectoryExistingAndKeepIt()
      throws IOException, ParserCacheException {
    LocalCache localCache =
        LocalCache.newInstance(
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
    HashCode strongFingerprint = Fingerprinter.getStrongFingerprint(filesystem, ImmutableList.of());
    localCache.storeBuildFileManifest(weakFingerprint, strongFingerprint, null);
    assertNotNull(localCachePath);
    assertTrue(filesystem.exists(wfpPath));
    assertTrue(filesystem.exists(newFilePath));
  }

  @Test
  public void stroreInLocalCacheAndGetFromLocalCacheAndVerifyMatch()
      throws IOException, ParserCacheException {
    LocalCache localCache =
        LocalCache.newInstance(
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
    filesystem.createNewFile(filesystem.getPath("Includes1"));
    filesystem.createNewFile(filesystem.getPath("includes2"));
    ImmutableList<String> includes = ImmutableList.of("/Includes1", "/includes2");
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
    HashCode strongFingerprinter = Fingerprinter.getStrongFingerprint(filesystem, includes);

    // Store in local cache
    localCache.storeBuildFileManifest(weakFingerprinter, strongFingerprinter, buildFileManifest);

    Path serializedDataFile =
        localCachePath
            .resolve(weakFingerprinter.toString())
            .resolve(strongFingerprinter.toString());
    assertTrue(checkExistsAfterConvertingToProperFilesystem(buildPath, serializedDataFile));

    // Get from local cache
    BuildFileManifest buildFileManifestResult =
        localCache.getBuildFileManifest(weakFingerprinter, strongFingerprinter);
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
