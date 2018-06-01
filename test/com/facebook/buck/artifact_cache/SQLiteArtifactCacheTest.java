/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.artifact_cache;

import static com.facebook.buck.artifact_cache.SQLiteArtifactCache.marshalMetadata;
import static com.facebook.buck.artifact_cache.SQLiteArtifactCache.unmarshalMetadata;
import static com.facebook.buck.artifact_cache.TwoLevelArtifactCacheDecorator.METADATA_KEY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.artifact_cache.config.CacheReadMode;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfo;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.io.file.BorrowablePath;
import com.facebook.buck.io.file.LazyPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SQLiteArtifactCacheTest {
  @Rule public TemporaryPaths tmpDir = new TemporaryPaths();

  private static final long MAX_INLINED_BYTES = 1024;

  private ProjectFilesystem filesystem;
  private Path fileA, fileB, fileC;
  private RuleKey ruleKeyA, ruleKeyB, ruleKeyC;
  private RuleKey contentHashA, contentHashB, contentHashC;
  private ArtifactInfo artifactInfoA, artifactInfoB, artifactInfoC;
  private Path emptyFile;

  private Path cacheDir;
  private LazyPath output;
  private SQLiteArtifactCache artifactCache;

  @Before
  public void setUp() throws InterruptedException, IOException, SQLException {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmpDir.getRoot());
    fileA = tmpDir.newFile("a");
    fileB = tmpDir.newFile("b");
    fileC = tmpDir.newFile("c");

    ruleKeyA = new RuleKey("aaaaaaaaaaaaaaaa");
    ruleKeyB = new RuleKey("bbbbbbbbbbbbbbbb");
    ruleKeyC = new RuleKey("cccccccccccccccc");
    contentHashA = new RuleKey("dddddddddddddddd");
    contentHashB = new RuleKey("eeeeeeeeeeeeeeee");
    contentHashC = new RuleKey("ffffffffffffffff");

    artifactInfoA = ArtifactInfo.builder().addRuleKeys(contentHashA).build();
    artifactInfoB = ArtifactInfo.builder().addRuleKeys(contentHashB).build();
    artifactInfoC = ArtifactInfo.builder().addRuleKeys(contentHashC).build();

    emptyFile = tmpDir.newFile(".empty");

    cacheDir = tmpDir.newFolder();
    output = LazyPath.ofInstance(cacheDir.resolve(".output"));
  }

  @After
  public void tearDown() {
    if (artifactCache != null) {
      artifactCache.close();
    }
  }

  private SQLiteArtifactCache cache(Optional<Long> maxCacheSizeBytes)
      throws IOException, SQLException {
    return new SQLiteArtifactCache(
        "sqlite",
        filesystem,
        cacheDir,
        BuckEventBusForTests.newInstance(),
        maxCacheSizeBytes,
        Optional.of(MAX_INLINED_BYTES),
        CacheReadMode.READWRITE);
  }

  /**
   * Writes an artifact small enough to inline in the database.
   *
   * <p>Uses the pathname to guarantee that files have different content for testing overwrites.
   */
  private void writeInlinedArtifact(Path file) throws IOException {
    Files.write(
        file,
        file.toString().getBytes(UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  /**
   * Writes an artifact large enough to be stored on disk.
   *
   * <p>Uses the pathname to guarantee that files have different content for testing overwrites.
   */
  private void writeFileArtifact(Path file) throws IOException {
    byte[] toWrite = file.toString().getBytes(UTF_8);
    for (int i = 0; i <= MAX_INLINED_BYTES / toWrite.length; i++) {
      Files.write(file, toWrite, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
  }

  @Test
  public void testCacheCreation() throws IOException, SQLException {
    artifactCache = cache(Optional.of(0L));
  }

  @Test
  public void testMetadataFetchMiss() throws IOException, SQLException {
    artifactCache = cache(Optional.of(0L));
    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                artifactCache.fetchAsync(null, ruleKeyA, LazyPath.ofInstance(emptyFile)))
            .getType());
  }

  @Test
  public void testContentFetchMiss() throws IOException, SQLException {
    artifactCache = cache(Optional.of(0L));
    assertEquals(
        CacheResultType.MISS,
        Futures.getUnchecked(
                artifactCache.fetchAsync(null, contentHashA, LazyPath.ofInstance(fileA)))
            .getType());
  }

  @Test
  public void testMetadataStoreAndFetchHit() throws IOException, SQLException {
    artifactCache = cache(Optional.empty());
    artifactCache.store(
        ArtifactInfo.builder()
            .addRuleKeys(ruleKeyA)
            .putMetadata(METADATA_KEY, contentHashA.toString())
            .putMetadata(BuildInfo.MetadataKey.RULE_KEY, ruleKeyA.toString())
            .build(),
        BorrowablePath.notBorrowablePath(emptyFile));

    assertThat(artifactCache.metadataRuleKeys(), Matchers.contains(ruleKeyA));

    CacheResult result = Futures.getUnchecked(artifactCache.fetchAsync(null, ruleKeyA, output));
    assertEquals(CacheResultType.HIT, result.getType());
    assertThat(result.getMetadata(), Matchers.hasKey(METADATA_KEY));
    assertEquals(contentHashA.toString(), result.getMetadata().get(METADATA_KEY));
    assertThat(result.getMetadata(), Matchers.hasKey(BuildInfo.MetadataKey.RULE_KEY));
    assertEquals(ruleKeyA.toString(), result.getMetadata().get(BuildInfo.MetadataKey.RULE_KEY));
    assertEquals(0, result.getArtifactSizeBytes());
  }

  @Test
  public void testMetadataStoreOverwrite() throws IOException, SQLException {
    artifactCache = cache(Optional.empty());
    ArtifactInfo oldMapping =
        ArtifactInfo.builder()
            .addRuleKeys(ruleKeyA)
            .putMetadata(METADATA_KEY, contentHashA.toString())
            .build();
    artifactCache.store(oldMapping, BorrowablePath.notBorrowablePath(emptyFile));

    ArtifactInfo newMapping =
        ArtifactInfo.builder()
            .addRuleKeys(ruleKeyA)
            .putMetadata(METADATA_KEY, contentHashB.toString())
            .build();
    artifactCache.store(newMapping, BorrowablePath.notBorrowablePath(emptyFile));

    assertThat(artifactCache.metadataRuleKeys(), Matchers.contains(ruleKeyA));

    CacheResult result = Futures.getUnchecked(artifactCache.fetchAsync(null, ruleKeyA, output));
    assertEquals(CacheResultType.HIT, result.getType());
    assertThat(result.getMetadata(), Matchers.hasKey(METADATA_KEY));
    assertEquals(contentHashB.toString(), result.getMetadata().get(METADATA_KEY));
    assertEquals(0, result.getArtifactSizeBytes());
  }

  @Test
  public void testInlinedContentStoreAndFetchHit() throws IOException, SQLException {
    artifactCache = cache(Optional.empty());
    writeInlinedArtifact(fileA);
    artifactCache.store(artifactInfoA, BorrowablePath.notBorrowablePath(fileA));

    assertThat(artifactCache.inlinedArtifactContentHashes(), Matchers.contains(contentHashA));

    CacheResult result = Futures.getUnchecked(artifactCache.fetchAsync(null, contentHashA, output));
    assertEquals(CacheResultType.HIT, result.getType());
    assertThat(result.getMetadata(), Matchers.anEmptyMap());
    assertEquals(filesystem.getFileSize(fileA), result.getArtifactSizeBytes());
    assertArrayEquals(Files.readAllBytes(fileA), Files.readAllBytes(output.get()));
  }

  @Test
  public void testFileContentStoreAndFetchHit() throws IOException, SQLException {
    artifactCache = cache(Optional.empty());
    writeFileArtifact(fileA);
    artifactCache.store(artifactInfoA, BorrowablePath.notBorrowablePath(fileA));

    assertThat(artifactCache.directoryFileContentHashes(), Matchers.contains(contentHashA));

    CacheResult result = Futures.getUnchecked(artifactCache.fetchAsync(null, contentHashA, output));
    assertEquals(CacheResultType.HIT, result.getType());
    assertThat(result.getMetadata(), Matchers.anEmptyMap());
    assertEquals(filesystem.getFileSize(fileA), result.getArtifactSizeBytes());
  }

  @Test
  public void testContentStoreAlreadyExists() throws IOException, SQLException {
    artifactCache = cache(Optional.empty());
    writeInlinedArtifact(fileA);
    artifactCache.store(artifactInfoA, BorrowablePath.notBorrowablePath(fileA));

    // skip storing because content hash already exists
    artifactCache.store(artifactInfoA, BorrowablePath.notBorrowablePath(fileA));

    assertThat(artifactCache.inlinedArtifactContentHashes(), Matchers.contains(contentHashA));

    CacheResult result = Futures.getUnchecked(artifactCache.fetchAsync(null, contentHashA, output));
    assertEquals(CacheResultType.HIT, result.getType());
    assertThat(result.getMetadata(), Matchers.anEmptyMap());
    assertEquals(filesystem.getFileSize(fileA), result.getArtifactSizeBytes());
    assertArrayEquals(Files.readAllBytes(fileA), Files.readAllBytes(output.get()));
  }

  @Test
  public void testCacheStoresAndBorrowsPaths() throws IOException, SQLException {
    artifactCache = cache(Optional.empty());

    writeFileArtifact(fileA);
    writeFileArtifact(fileB);

    artifactCache.store(artifactInfoA, BorrowablePath.notBorrowablePath(fileA));
    artifactCache.store(artifactInfoB, BorrowablePath.borrowablePath(fileB));

    assertTrue(Files.exists(fileA));
    assertFalse(Files.exists(fileB));
  }

  @Test
  public void testDeleteMetadata() throws Exception {
    artifactCache = cache(Optional.of(0L));
    Timestamp time = Timestamp.from(Instant.now().minus(Duration.ofDays(8)));

    artifactCache.insertMetadata(ruleKeyA, ImmutableMap.of(), time);
    artifactCache.insertMetadata(ruleKeyB, ImmutableMap.of(), time);

    assertThat(artifactCache.metadataRuleKeys(), Matchers.hasSize(2));

    artifactCache.store(
        ArtifactInfo.builder().addRuleKeys(ruleKeyC).putMetadata(METADATA_KEY, "foo").build(),
        BorrowablePath.notBorrowablePath(emptyFile));

    assertThat(artifactCache.metadataRuleKeys(), Matchers.hasSize(3));

    artifactCache.removeOldMetadata().get();
    assertThat(artifactCache.metadataRuleKeys(), Matchers.contains(ruleKeyC));
  }

  @Test
  public void testNoStoreMisses() throws Exception {
    artifactCache = cache(Optional.of(0L));
    Timestamp time = Timestamp.from(Instant.now().minus(Duration.ofDays(7)));

    writeFileArtifact(fileA);
    writeFileArtifact(fileB);

    artifactCache.insertContent(contentHashA, BorrowablePath.notBorrowablePath(fileA), time);
    artifactCache.insertContent(contentHashB, BorrowablePath.notBorrowablePath(fileB), time);

    assertThat(artifactCache.directoryFileContentHashes(), Matchers.hasSize(2));

    artifactCache.store(artifactInfoC, BorrowablePath.notBorrowablePath(emptyFile));

    // remove fileA and fileB and stop when size limit reached, leaving fileC
    artifactCache.removeOldContent().get();

    assertThat(artifactCache.directoryFileContentHashes(), Matchers.empty());
    assertThat(artifactCache.inlinedArtifactContentHashes(), Matchers.contains(contentHashC));
  }

  @Test
  public void testDeleteNothingAfterStore() throws Exception {
    artifactCache = cache(Optional.of(4 * MAX_INLINED_BYTES));

    writeFileArtifact(fileA);
    writeFileArtifact(fileB);
    writeFileArtifact(fileC);

    artifactCache.store(artifactInfoA, BorrowablePath.borrowablePath(fileA));
    artifactCache.store(artifactInfoB, BorrowablePath.borrowablePath(fileB));
    artifactCache.store(artifactInfoC, BorrowablePath.borrowablePath(fileC));

    artifactCache.removeOldContent().get();
    assertThat(
        artifactCache.directoryFileContentHashes(),
        Matchers.containsInAnyOrder(contentHashA, contentHashB, contentHashC));
  }

  @Test
  public void testDeleteNothingAfterStoreNoLimit() throws Exception {
    artifactCache = cache(Optional.empty());

    writeFileArtifact(fileA);
    writeFileArtifact(fileB);
    writeFileArtifact(fileC);

    artifactCache.store(artifactInfoA, BorrowablePath.borrowablePath(fileA));
    artifactCache.store(artifactInfoB, BorrowablePath.borrowablePath(fileB));
    artifactCache.store(artifactInfoC, BorrowablePath.borrowablePath(fileC));

    artifactCache.removeOldContent().get();
    assertThat(
        artifactCache.directoryFileContentHashes(),
        Matchers.containsInAnyOrder(contentHashA, contentHashB, contentHashC));
  }

  @Test
  public void testDeleteAfterStoreWhenFull() throws Exception {
    artifactCache = cache(Optional.of(2 * MAX_INLINED_BYTES));

    writeInlinedArtifact(fileA);
    writeFileArtifact(fileB);
    writeFileArtifact(fileC);

    artifactCache.insertContent(
        contentHashA,
        BorrowablePath.borrowablePath(fileA),
        Timestamp.from(Instant.now().minus(Duration.ofDays(3))));
    assertThat(artifactCache.inlinedArtifactContentHashes(), Matchers.hasSize(1));

    artifactCache.insertContent(
        contentHashB,
        BorrowablePath.borrowablePath(fileB),
        Timestamp.from(Instant.now().minus(Duration.ofDays(2))));
    assertThat(artifactCache.inlinedArtifactContentHashes(), Matchers.hasSize(1));
    assertThat(artifactCache.directoryFileContentHashes(), Matchers.hasSize(1));

    // cache is within limits, so nothing evicted
    artifactCache.removeOldContent().get();
    assertThat(artifactCache.inlinedArtifactContentHashes(), Matchers.hasSize(1));
    assertThat(artifactCache.directoryFileContentHashes(), Matchers.hasSize(1));

    // add fileC, causing cache to exceed max size
    artifactCache.store(artifactInfoC, BorrowablePath.borrowablePath(fileC));
    ImmutableList<RuleKey> filesNotDeleted = artifactCache.directoryFileContentHashes();
    int remaining = filesNotDeleted.size() + artifactCache.inlinedArtifactContentHashes().size();
    assertEquals(remaining, 3);

    artifactCache.removeOldContent().get();
    filesNotDeleted = artifactCache.directoryFileContentHashes();
    remaining = filesNotDeleted.size() + artifactCache.inlinedArtifactContentHashes().size();
    assertThat(remaining, Matchers.lessThan(3));
    assertThat(filesNotDeleted, Matchers.hasItem(contentHashC));
  }

  @Test
  public void testCacheStoreMultipleKeys() throws IOException, SQLException {
    artifactCache = cache(Optional.empty());
    artifactCache.store(
        ArtifactInfo.builder()
            .addRuleKeys(ruleKeyA, ruleKeyB, ruleKeyC)
            .putMetadata(METADATA_KEY, contentHashA.toString())
            .build(),
        BorrowablePath.notBorrowablePath(emptyFile));

    CacheResult resultA = Futures.getUnchecked(artifactCache.fetchAsync(null, ruleKeyA, output));
    assertEquals(CacheResultType.HIT, resultA.getType());
    assertThat(resultA.getMetadata(), Matchers.hasKey(METADATA_KEY));
    assertEquals(contentHashA.toString(), resultA.getMetadata().get(METADATA_KEY));

    CacheResult resultB = Futures.getUnchecked(artifactCache.fetchAsync(null, ruleKeyB, output));
    assertEquals(CacheResultType.HIT, resultB.getType());
    assertThat(resultB.getMetadata(), Matchers.hasKey(METADATA_KEY));
    assertEquals(contentHashA.toString(), resultB.getMetadata().get(METADATA_KEY));

    CacheResult resultC = Futures.getUnchecked(artifactCache.fetchAsync(null, ruleKeyA, output));
    assertEquals(CacheResultType.HIT, resultC.getType());
    assertThat(resultC.getMetadata(), Matchers.hasKey(METADATA_KEY));
    assertEquals(contentHashA.toString(), resultC.getMetadata().get(METADATA_KEY));
  }

  @Test
  public void testOneLevelCache() throws IOException, SQLException {
    artifactCache = cache(Optional.empty());
    writeFileArtifact(fileA);

    artifactCache.store(
        ArtifactInfo.builder()
            .addRuleKeys(ruleKeyA)
            .putMetadata(BuildInfo.MetadataKey.TARGET, "foo")
            .build(),
        BorrowablePath.notBorrowablePath(fileA));

    CacheResult result = Futures.getUnchecked(artifactCache.fetchAsync(null, ruleKeyA, output));
    assertEquals(CacheResultType.HIT, result.getType());
    assertThat(result.getMetadata(), Matchers.hasKey(BuildInfo.MetadataKey.TARGET));
    assertEquals(result.getMetadata().get(BuildInfo.MetadataKey.TARGET), "foo");
    assertEquals(result.getArtifactSizeBytes(), Files.size(fileA));
    assertArrayEquals(Files.readAllBytes(output.get()), Files.readAllBytes(fileA));
  }

  @Test
  public void testMarshalMetadata() throws IOException {
    byte[] expected = new byte[4];
    assertThat(marshalMetadata(ImmutableMap.of()), Matchers.equalTo(expected));

    expected = new byte[] {0, 0, 0, 1, 0, 3, 'f', 'o', 'o', 0, 0, 0, 3, 'b', 'a', 'r'};
    assertThat(marshalMetadata(ImmutableMap.of("foo", "bar")), Matchers.equalTo(expected));

    expected =
        new byte[] {
          0, 0, 0, 2, 0, 1, 'A', 0, 0, 0, 2, 'B', 'C', 0, 3, 'D', 'E', 'F', 0, 0, 0, 4, 'G', 'H',
          'I', 'J'
        };
    assertThat(
        marshalMetadata(ImmutableMap.of("A", "BC", "DEF", "GHIJ")), Matchers.equalTo(expected));
  }

  @Test
  public void testUnmarshalMetadata() throws IOException {
    assertThat(unmarshalMetadata(marshalMetadata(ImmutableMap.of())), Matchers.anEmptyMap());

    ImmutableMap<String, String> metadata = ImmutableMap.of("A", "a", "B", "b");
    assertThat(unmarshalMetadata(marshalMetadata(metadata)), Matchers.equalTo(metadata));
  }

  @Test
  public void testFolderLevelsForRuleKeys() throws IOException, SQLException {
    artifactCache = cache(Optional.empty());
    assertEquals(cacheDir.resolve("0123"), artifactCache.getArtifactPath(new RuleKey("0123")));
    assertEquals(
        cacheDir.resolve("45").resolve("67").resolve("456789abcdef"),
        artifactCache.getArtifactPath(new RuleKey("456789abcdef")));
  }
}
