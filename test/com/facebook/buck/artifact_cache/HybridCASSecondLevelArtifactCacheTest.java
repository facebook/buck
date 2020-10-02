/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.artifact_cache;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.remoteexecution.ContentAddressedStorageClient;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.remoteexecution.util.LocalContentAddressedStorage;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class HybridCASSecondLevelArtifactCacheTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  private InMemoryArtifactCache baseCache;
  private ProjectFilesystem projectFilesystem;
  private BuckEventBus buckEventBus;

  @Before
  public void setUp() {
    baseCache = new InMemoryArtifactCache();
    projectFilesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    buckEventBus = BuckEventBusForTests.newInstance();
  }

  private void writeString(AbsPath p, String s) throws IOException {
    Files.write(
        p.getPath(),
        s.getBytes(Charset.defaultCharset()),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  @Test
  public void nonCasStoreFetch() throws IOException {
    HybridCASSecondLevelArtifactCache cache =
        new HybridCASSecondLevelArtifactCache(
            baseCache, projectFilesystem, buckEventBus, Optional.empty(), false, 0, 0);
    String testContents = "hi, i am a test file.";
    AbsPath testFile = tmp.newFile();

    writeString(testFile, testContents);

    String ck =
        Futures.getUnchecked(
            cache.storeAsync(
                ArtifactInfo.builder().build(),
                BorrowablePath.notBorrowablePath(testFile.getPath())));

    writeString(testFile, "");
    CacheResult result =
        Futures.getUnchecked(cache.fetchAsync(null, ck, LazyPath.ofInstance(testFile)));

    assertEquals(1, baseCache.getArtifactCount());
    assertEquals(CacheResultType.HIT, result.getType());
    assertEquals(testContents, new String(Files.readAllBytes(testFile.getPath())));
  }

  @Test
  public void casStoreFetch() throws IOException {
    ContentAddressedStorageClient casClient =
        new LocalContentAddressedStorage(
            tmp.newFolder().getPath(), new GrpcProtocol(), buckEventBus);
    HybridCASSecondLevelArtifactCache cache =
        new HybridCASSecondLevelArtifactCache(
            baseCache, projectFilesystem, buckEventBus, Optional.of(casClient), true, 100, 100);

    String testContents = "hi, i am a CAS test file. O__O";
    AbsPath testFile = tmp.newFile();
    writeString(testFile, testContents);

    String ck =
        Futures.getUnchecked(
            cache.storeAsync(
                ArtifactInfo.builder().build(),
                BorrowablePath.notBorrowablePath(testFile.getPath())));

    writeString(testFile, "");
    CacheResult result =
        Futures.getUnchecked(cache.fetchAsync(null, ck, LazyPath.ofInstance(testFile)));

    assertEquals(0, baseCache.getArtifactCount());
    assertEquals(CacheResultType.HIT, result.getType());
    assertEquals(testContents, new String(Files.readAllBytes(testFile.getPath())));
  }

  @Test(expected = HumanReadableException.class)
  public void badDigestFetch() throws IOException {
    ContentAddressedStorageClient casClient =
        new LocalContentAddressedStorage(
            tmp.newFolder().getPath(), new GrpcProtocol(), buckEventBus);
    HybridCASSecondLevelArtifactCache cache =
        new HybridCASSecondLevelArtifactCache(
            baseCache, projectFilesystem, buckEventBus, Optional.of(casClient), true, 100, 100);

    Futures.getUnchecked(cache.fetchAsync(null, "cas/Hello!", LazyPath.ofInstance(tmp.newFile())));
  }

  @Test(expected = Exception.class)
  public void missingDigestFetch() throws IOException {
    ContentAddressedStorageClient casClient =
        new LocalContentAddressedStorage(
            tmp.newFolder().getPath(), new GrpcProtocol(), buckEventBus);
    HybridCASSecondLevelArtifactCache cache =
        new HybridCASSecondLevelArtifactCache(
            baseCache, projectFilesystem, buckEventBus, Optional.of(casClient), true, 100, 100);

    Futures.getUnchecked(
        cache.fetchAsync(
            null,
            "cas/7884234c0748f0e1bfd8de1393defa38965bc13e:396",
            LazyPath.ofInstance(tmp.newFile())));
  }

  @Test(expected = RuntimeException.class)
  public void unhashablePathStore() throws IOException {
    ContentAddressedStorageClient casClient =
        new LocalContentAddressedStorage(
            tmp.newFolder().getPath(), new GrpcProtocol(), buckEventBus);
    HybridCASSecondLevelArtifactCache cache =
        new HybridCASSecondLevelArtifactCache(
            baseCache,
            new FakeProjectFilesystem(),
            buckEventBus,
            Optional.of(casClient),
            true,
            100,
            100);

    Futures.getUnchecked(
        cache.storeAsync(
            ArtifactInfo.builder().build(),
            BorrowablePath.notBorrowablePath(tmp.newFile().getPath())));
  }

  @Test
  public void oldStyleRkFetch() throws IOException {
    HybridCASSecondLevelArtifactCache cache =
        new HybridCASSecondLevelArtifactCache(
            baseCache, projectFilesystem, buckEventBus, Optional.empty(), false, 0, 0);
    String testContents = "hi, i am a test file.";
    AbsPath testFile = tmp.newFile();

    writeString(testFile, testContents);

    String digest = projectFilesystem.computeSha1(testFile.getPath()) + "2c00";
    Futures.getUnchecked(
        baseCache.store(
            ArtifactInfo.builder().addRuleKeys(new RuleKey(digest)).build(),
            BorrowablePath.notBorrowablePath(testFile.getPath())));

    writeString(testFile, "");
    CacheResult result =
        Futures.getUnchecked(cache.fetchAsync(null, digest, LazyPath.ofInstance(testFile)));

    assertEquals(1, baseCache.getArtifactCount());
    assertEquals(CacheResultType.HIT, result.getType());
    assertEquals(testContents, new String(Files.readAllBytes(testFile.getPath())));
  }
}
