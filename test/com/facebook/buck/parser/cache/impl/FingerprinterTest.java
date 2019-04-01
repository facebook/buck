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

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.api.ImmutableBuildFileManifest;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class FingerprinterTest {
  @Rule public ExpectedException expectedThrownException = ExpectedException.none();

  private ProjectFilesystem fs;

  @Before
  public void setUp() {
    // JimFS has issues with absolute and relative Windows paths.
    assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));

    fs = FakeProjectFilesystem.createJavaOnlyFilesystem("/");
  }

  private BuckConfig getConfigForTest() {
    return FakeBuckConfig.builder()
        .setSections(
            "[parser]", "local_parser_cache_location = foobar", "parser_caching_enabled = barFoo")
        .setSections("[project]", "Z = Z", "Y = Y")
        .setFilesystem(fs)
        .build();
  }

  private static void addContentHash(Path filePath, ProjectFilesystem fs, Hasher hasher)
      throws IOException {
    hasher.putString(fs.computeSha256(filePath), StandardCharsets.UTF_8);
  }

  @Test
  public void calculateWeakFingerprint() {
    Config config = getConfigForTest().getConfig();
    Path buildFilePath = fs.getPath("Foo/Bar");
    HashCode weakFingerprintHash = Fingerprinter.getWeakFingerprint(buildFilePath, config);
    HashCode expectedFingerprint =
        Hashing.sha256()
            .newHasher()
            .putString(buildFilePath.toString(), StandardCharsets.UTF_8)
            .putBytes(config.getOrderIndependentHashCode().asBytes())
            .putString(Platform.detect().name(), StandardCharsets.UTF_8)
            .putString(Architecture.detect().name(), StandardCharsets.UTF_8)
            .hash();
    assertEquals(expectedFingerprint, weakFingerprintHash);
  }

  @Test
  public void calculateStrongFingerprintWithAllBuildFilesPresent() throws IOException {
    fs.mkdirs(fs.getPath("foo/bar"));
    fs.createNewFile(fs.getPath("foo/bar/FooBar.bzl"));
    fs.createNewFile(fs.getPath("foo/bar/BUCK"));
    fs.createNewFile(fs.getPath("foo/bar/BarFoo.bzl"));
    ImmutableSortedSet<String> includes =
        ImmutableSortedSet.of("/foo/bar/FooBar.bzl", "/foo/bar/BUCK", "/foo/bar/BarFoo.bzl");
    BuildFileManifest buildFileManifest =
        ImmutableBuildFileManifest.of(
            ImmutableMap.of(), includes, ImmutableMap.of(), Optional.empty(), ImmutableList.of());
    FakeFileHashCache fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                fs.getPath("/foo/bar/FooBar.bzl"), HashCode.fromBytes(new byte[] {1}),
                fs.getPath("/foo/bar/BUCK"), HashCode.fromBytes(new byte[] {2}),
                fs.getPath("/foo/bar/BarFoo.bzl"), HashCode.fromBytes(new byte[] {3})));
    HashCode strongFingerprintHash =
        Fingerprinter.getStrongFingerprint(fs, includes, fileHashCache);
    ImmutableSortedSet<String> sortedIncludes =
        ImmutableSortedSet.copyOf(buildFileManifest.getIncludes());
    Hasher hasher = Hashing.sha256().newHasher();
    for (String value : sortedIncludes) {
      Path includePath = fs.getPath(value);
      hasher.putString(fs.relativize(includePath).toString(), StandardCharsets.UTF_8);
      hasher.putBytes(fileHashCache.get(includePath).asBytes());
    }

    HashCode expectedHash = hasher.hash();
    assertEquals(expectedHash, strongFingerprintHash);
  }

  @Test
  public void strongFingerprintWithMissingBuildFileThrows() throws IOException {
    expectedThrownException.expect(NoSuchFileException.class);
    expectedThrownException.expectMessage("/foo/bar/BUCK");

    fs.mkdirs(fs.getPath("foo/bar"));
    fs.createNewFile(fs.getPath("foo/bar/FooBar.bzl"));
    fs.createNewFile(fs.getPath("foo/bar/BarFoo.bzl"));
    ImmutableSortedSet<String> includes =
        ImmutableSortedSet.of("/foo/bar/FooBar.bzl", "/foo/bar/BUCK", "/foo/bar/BarFoo.bzl");
    BuildFileManifest buildFileManifest =
        ImmutableBuildFileManifest.of(
            ImmutableMap.of(), includes, ImmutableMap.of(), Optional.empty(), ImmutableList.of());

    Fingerprinter.getStrongFingerprint(
        fs,
        buildFileManifest.getIncludes(),
        new FakeFileHashCache(
            ImmutableMap.of(
                fs.getPath("/foo/bar/FooBar.bzl"), HashCode.fromBytes(new byte[] {1}),
                fs.getPath("/foo/bar/BarFoo.bzl"), HashCode.fromBytes(new byte[] {3}))));
  }
}
