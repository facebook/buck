/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.core.build.engine.manifest;

import static org.junit.Assert.assertThat;

import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ManifestTest {

  private static final SourcePathResolver RESOLVER =
      DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));

  @Test
  public void toMap() {
    ImmutableMap<RuleKey, ImmutableMap<String, HashCode>> entries =
        ImmutableMap.of(new RuleKey("aa"), ImmutableMap.of("foo/bar.h", HashCode.fromInt(20)));
    assertThat(
        ManifestUtil.toMap(ManifestUtil.fromMap(new RuleKey("cc"), entries)),
        Matchers.equalTo(entries));
  }

  @Test
  public void emptyManifest() {
    assertThat(ManifestUtil.toMap(new Manifest(new RuleKey("cc"))).entrySet(), Matchers.empty());
  }

  @Test
  public void serialize() throws IOException {
    ImmutableMap<RuleKey, ImmutableMap<String, HashCode>> entries =
        ImmutableMap.of(new RuleKey("aa"), ImmutableMap.of("foo/bar.h", HashCode.fromInt(20)));
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    ManifestUtil.fromMap(new RuleKey("cc"), entries).serialize(byteArrayOutputStream);
    Manifest deserialized =
        new Manifest(new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));
    assertThat(ManifestUtil.toMap(deserialized), Matchers.equalTo(entries));
  }

  @Test
  public void addEntry() throws IOException {
    Manifest manifest = new Manifest(new RuleKey("cc"));
    RuleKey key = new RuleKey("aa");
    SourcePath input = FakeSourcePath.of("input.h");
    HashCode hashCode = HashCode.fromInt(20);
    FileHashCache fileHashCache =
        new FakeFileHashCache(ImmutableMap.of(RESOLVER.getAbsolutePath(input), hashCode));
    manifest.addEntry(fileHashCache, key, RESOLVER, ImmutableSet.of(input), ImmutableSet.of(input));
    assertThat(
        ManifestUtil.toMap(manifest),
        Matchers.equalTo(
            ImmutableMap.of(
                key, ImmutableMap.of(RESOLVER.getRelativePath(input).toString(), hashCode))));
  }

  @Test
  public void addEntryFromArchive() throws IOException {
    Manifest manifest = new Manifest(new RuleKey("cc"));
    RuleKey key = new RuleKey("aa");
    SourcePath input =
        ArchiveMemberSourcePath.of(FakeSourcePath.of("somewhere/a.jar"), Paths.get("Member.class"));
    HashCode hashCode = HashCode.fromInt(20);
    FileHashCache fileHashCache =
        new FakeFileHashCache(
            new HashMap<>(),
            ImmutableMap.of(RESOLVER.getAbsoluteArchiveMemberPath(input), hashCode),
            new HashMap<>());
    manifest.addEntry(fileHashCache, key, RESOLVER, ImmutableSet.of(input), ImmutableSet.of(input));
    assertThat(
        ManifestUtil.toMap(manifest),
        Matchers.equalTo(
            ImmutableMap.of(
                key,
                ImmutableMap.of(
                    RESOLVER.getRelativeArchiveMemberPath(input).toString(), hashCode))));
  }

  @Test
  public void addEntryWithSourcePathsThatHaveSameRelativePaths() throws IOException {
    RuleKey key = new RuleKey("aa");

    Path tmp1 = Files.createTempDirectory("tmp1");
    ProjectFilesystem filesystem1 = new FakeProjectFilesystem(tmp1);
    SourcePath input1 = PathSourcePath.of(filesystem1, Paths.get("input.h"));
    HashCode hashCode1 = HashCode.fromInt(1);

    Path tmp2 = Files.createTempDirectory("tmp2");
    ProjectFilesystem filesystem2 = new FakeProjectFilesystem(tmp2);
    SourcePath input2 = PathSourcePath.of(filesystem2, Paths.get("input.h"));
    HashCode hashCode2 = HashCode.fromInt(1);

    FileHashCache fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                RESOLVER.getAbsolutePath(input1),
                hashCode1,
                RESOLVER.getAbsolutePath(input2),
                hashCode2));

    Manifest manifest1 = new Manifest(new RuleKey("cc"));
    manifest1.addEntry(
        fileHashCache, key, RESOLVER, ImmutableSet.of(input1, input2), ImmutableSet.of(input1));
    assertThat(
        ManifestUtil.toMap(manifest1),
        Matchers.equalTo(
            ImmutableMap.of(
                key,
                ImmutableMap.of(
                    RESOLVER.getRelativePath(input1).toString(),
                    Manifest.hashSourcePathGroup(
                        fileHashCache, RESOLVER, ImmutableList.of(input1, input2))))));

    Manifest manifest2 = new Manifest(new RuleKey("cc"));
    manifest2.addEntry(
        fileHashCache, key, RESOLVER, ImmutableSet.of(input1, input2), ImmutableSet.of(input2));
    assertThat(
        ManifestUtil.toMap(manifest2),
        Matchers.equalTo(
            ImmutableMap.of(
                key,
                ImmutableMap.of(
                    RESOLVER.getRelativePath(input2).toString(),
                    Manifest.hashSourcePathGroup(
                        fileHashCache, RESOLVER, ImmutableList.of(input1, input2))))));
  }

  @Test
  public void lookupMatch() throws IOException {
    RuleKey key = new RuleKey("aa");
    SourcePath input = FakeSourcePath.of("input.h");
    HashCode hashCode = HashCode.fromInt(20);
    Manifest manifest =
        ManifestUtil.fromMap(
            new RuleKey("cc"),
            ImmutableMap.of(
                key, ImmutableMap.of(RESOLVER.getRelativePath(input).toString(), hashCode)));
    FileHashCache fileHashCache =
        new FakeFileHashCache(ImmutableMap.of(RESOLVER.getAbsolutePath(input), hashCode));
    assertThat(
        manifest.lookup(fileHashCache, RESOLVER, ImmutableSet.of(input)),
        Matchers.equalTo(Optional.of(key)));
  }

  @Test
  public void lookupMatchWithSourcePathsThatHaveSameRelativePaths() throws IOException {
    RuleKey key = new RuleKey("aa");

    Path tmp1 = Files.createTempDirectory("tmp1");
    ProjectFilesystem filesystem1 = new FakeProjectFilesystem(tmp1);
    SourcePath input1 = PathSourcePath.of(filesystem1, Paths.get("input.h"));
    HashCode hashCode1 = HashCode.fromInt(1);

    Path tmp2 = Files.createTempDirectory("tmp2");
    ProjectFilesystem filesystem2 = new FakeProjectFilesystem(tmp2);
    SourcePath input2 = PathSourcePath.of(filesystem2, Paths.get("input.h"));
    HashCode hashCode2 = HashCode.fromInt(1);

    FileHashCache fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                RESOLVER.getAbsolutePath(input1),
                hashCode1,
                RESOLVER.getAbsolutePath(input2),
                hashCode2));

    Manifest manifest1 =
        ManifestUtil.fromMap(
            new RuleKey("cc"),
            ImmutableMap.of(
                key,
                ImmutableMap.of(
                    RESOLVER.getRelativePath(input1).toString(),
                    Manifest.hashSourcePathGroup(
                        fileHashCache, RESOLVER, ImmutableList.of(input1, input2)))));
    assertThat(
        manifest1.lookup(fileHashCache, RESOLVER, ImmutableSet.of(input1, input2)),
        Matchers.equalTo(Optional.of(key)));

    Manifest manifest2 =
        ManifestUtil.fromMap(
            new RuleKey("cc"),
            ImmutableMap.of(
                key,
                ImmutableMap.of(
                    RESOLVER.getRelativePath(input2).toString(),
                    Manifest.hashSourcePathGroup(
                        fileHashCache, RESOLVER, ImmutableList.of(input1, input2)))));
    assertThat(
        manifest2.lookup(fileHashCache, RESOLVER, ImmutableSet.of(input1, input2)),
        Matchers.equalTo(Optional.of(key)));
  }

  @Test
  public void lookupHashMismatch() throws IOException {
    RuleKey key = new RuleKey("aa");
    SourcePath input = FakeSourcePath.of("input.h");
    Manifest manifest =
        ManifestUtil.fromMap(
            new RuleKey("cc"),
            ImmutableMap.of(
                key,
                ImmutableMap.of(RESOLVER.getRelativePath(input).toString(), HashCode.fromInt(1))));
    FileHashCache fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(RESOLVER.getAbsolutePath(input), HashCode.fromInt(2)));
    assertThat(
        manifest.lookup(fileHashCache, RESOLVER, ImmutableSet.of(input)),
        Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void lookupMissingHeader() throws IOException {
    RuleKey key = new RuleKey("aa");
    SourcePath input = FakeSourcePath.of("input.h");
    Manifest manifest =
        ManifestUtil.fromMap(
            new RuleKey("cc"),
            ImmutableMap.of(
                key,
                ImmutableMap.of(RESOLVER.getRelativePath(input).toString(), HashCode.fromInt(1))));
    FileHashCache fileHashCache = new FakeFileHashCache(ImmutableMap.of());
    assertThat(
        manifest.lookup(fileHashCache, RESOLVER, ImmutableSet.of(input)),
        Matchers.equalTo(Optional.empty()));
  }

  @Test
  public void lookupMatchAfterHashMismatch() throws IOException {
    RuleKey key1 = new RuleKey("aa");
    RuleKey key2 = new RuleKey("bb");
    SourcePath input = FakeSourcePath.of("input.h");
    Manifest manifest =
        ManifestUtil.fromMap(
            new RuleKey("cc"),
            ImmutableMap.of(
                key1,
                ImmutableMap.of(RESOLVER.getRelativePath(input).toString(), HashCode.fromInt(1)),
                key2,
                ImmutableMap.of(RESOLVER.getRelativePath(input).toString(), HashCode.fromInt(2))));
    FileHashCache fileHashCache =
        new FakeFileHashCache(
            ImmutableMap.of(RESOLVER.getAbsolutePath(input), HashCode.fromInt(2)));
    assertThat(
        manifest.lookup(fileHashCache, RESOLVER, ImmutableSet.of(input)),
        Matchers.equalTo(Optional.of(key2)));
  }

  @Test
  public void size() {
    assertThat(new Manifest(new RuleKey("cc")).size(), Matchers.equalTo(0));
    assertThat(
        ManifestUtil.fromMap(
                new RuleKey("cc"),
                ImmutableMap.of(new RuleKey("aa"), ImmutableMap.of("foo.h", HashCode.fromInt(0))))
            .size(),
        Matchers.equalTo(1));
    assertThat(
        ManifestUtil.fromMap(
                new RuleKey("cc"),
                ImmutableMap.of(
                    new RuleKey("aa"),
                    ImmutableMap.of("foo.h", HashCode.fromInt(0)),
                    new RuleKey("bb"),
                    ImmutableMap.of("bar.h", HashCode.fromInt(0))))
            .size(),
        Matchers.equalTo(2));
  }

  @Test
  public void stats() {
    assertThat(
        ManifestUtil.fromMap(
                new RuleKey("cc"),
                ImmutableMap.of(
                    new RuleKey("aa"),
                    ImmutableMap.of("foo.h", HashCode.fromInt(0)),
                    new RuleKey("bb"),
                    ImmutableMap.of("bar.h", HashCode.fromInt(0))))
            .getStats(),
        Matchers.equalTo(
            ManifestStats.builder().setNumDepFiles(2).setNumInputs(2).setNumHashes(1).build()));
  }
}
