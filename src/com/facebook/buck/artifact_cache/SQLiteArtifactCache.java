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

import com.facebook.buck.io.BorrowablePath;
import com.facebook.buck.io.LazyPath;
import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.BuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.sqlite.RetryBusyHandler;
import com.facebook.buck.sqlite.SQLiteUtils;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.sqlite.BusyHandler;
import org.sqlite.SQLiteConfig;

/**
 * Implementation of {@link ArtifactCache} using SQLite.
 *
 * <p>Cache entries are either metadata or content. All metadata contains a mapping to a content
 * entry. Content entries with sufficiently small content will have their artifacts inlined into the
 * database for improved performance.
 */
public class SQLiteArtifactCache implements ArtifactCache {

  private static final Logger LOG = Logger.get(SQLiteArtifactCache.class);

  private static final ArtifactCacheMode CACHE_MODE = ArtifactCacheMode.sqlite;
  // How much of the max size to leave if we decide to delete old files.
  private static final float MAX_BYTES_TRIM_RATIO = 2 / 3f;
  private static final String TMP_EXTENSION = ".tmp";
  private static final long DEFAULT_MAX_INLINED_BYTES = 0;
  private static final Duration DEFAULT_EVICTION_TIME = Duration.ofDays(7);

  private final String name;
  private final ProjectFilesystem filesystem;
  private final Path cacheDir;
  private final Optional<Long> maxCacheSizeBytes;
  private final Optional<Long> maxBytesAfterDeletion;
  private final long maxInlinedBytes;
  private final CacheReadMode cacheMode;

  private final ConnectionInfo db;

  static {
    SQLiteUtils.initialize();
  }

  SQLiteArtifactCache(
      String name,
      ProjectFilesystem filesystem,
      Path cacheDir,
      Optional<Long> maxCacheSizeBytes,
      Optional<Long> maxInlinedSizeBytes,
      CacheReadMode cacheMode)
      throws IOException, SQLException {
    this.name = name;
    this.filesystem = filesystem;
    this.cacheDir = cacheDir;
    this.maxCacheSizeBytes = maxCacheSizeBytes;
    this.maxBytesAfterDeletion =
        maxCacheSizeBytes.map(size -> (long) (size * MAX_BYTES_TRIM_RATIO));
    this.maxInlinedBytes = maxInlinedSizeBytes.orElse(DEFAULT_MAX_INLINED_BYTES);
    this.cacheMode = cacheMode;

    try {
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException e) {
      throw new SQLException("could not load SQLite class", e);
    }

    // Check first, as mkdirs will fail if the path is a symlink.
    if (!filesystem.exists(cacheDir)) {
      filesystem.mkdirs(cacheDir);
    } else if (!filesystem.isDirectory(cacheDir)) {
      throw new IOException(
          String.format("Cache path [%s] already exists and is not a directory.", cacheDir));
    }

    this.db = new ConnectionInfo(cacheDir);
  }

  @Override
  public ListenableFuture<CacheResult> fetchAsync(RuleKey ruleKey, LazyPath output) {
    return Futures.immediateFuture(fetch(ruleKey, output));
  }

  private CacheResult fetch(RuleKey ruleKey, LazyPath output) {
    CacheResult artifactFetchResult = maybeFetchArtifact(ruleKey, output);
    if (artifactFetchResult.getType() == CacheResultType.HIT
        || artifactFetchResult.getType() == CacheResultType.ERROR) {
      return artifactFetchResult;
    } else {
      return fetchMetadata(ruleKey, output);
    }
  }

  private CacheResult maybeFetchArtifact(RuleKey contentHash, LazyPath output) {
    CacheResult result =
        CacheResult.error(
            name,
            CACHE_MODE,
            String.format("Artifact fetch(%s, %s) stopped unexpectedly", contentHash, output));
    try {
      ResultSet rs = db.selectContent(contentHash);
      if (rs.next()) {
        byte[] artifact = rs.getBytes(1);
        String filepath = rs.getString(2);

        if (Objects.nonNull(artifact)) {
          // artifact was inlined into the database as a blob
          filesystem.writeBytesToPath(artifact, output.get());
        } else if (filesystem.exists(filesystem.resolve(filepath))) {
          // artifact stored on disk with path in database
          filesystem.copyFile(filesystem.resolve(filepath), output.get());
        } else {
          // artifact stored on disk was removed by another cache, remove database entry
          db.deleteContent(contentHash);
          return result = CacheResult.miss();
        }

        long size = rs.getLong(3);
        db.accessContent(contentHash);

        return result = CacheResult.hit(name, CACHE_MODE, ImmutableMap.of(), size);
      }

      return result = CacheResult.miss();
    } catch (IOException | SQLException e) {
      LOG.warn(e, "Artifact fetch(%s, %s) error", contentHash, output);
      return result =
          CacheResult.error(
              name, CACHE_MODE, String.format("%s: %s", e.getClass(), e.getMessage()));
    } finally {
      LOG.verbose(
          "Artifact fetch(%s, %s) cache %s",
          contentHash, output, result.getType().isSuccess() ? "hit" : "miss");
    }
  }

  private CacheResult fetchMetadata(RuleKey ruleKey, LazyPath output) {
    CacheResult result =
        CacheResult.error(
            name,
            CACHE_MODE,
            String.format("Metadata fetch(%s, %s) stopped unexpectedly", ruleKey, output));
    try {
      ResultSet rs = db.selectMetadata(ruleKey);
      if (rs.next()) {
        byte[] metadata = rs.getBytes(1);
        db.accessMetadata(ruleKey);
        output.get(); // for MultiArtifactCache, force evaluation of the output path

        return result = CacheResult.hit(name, CACHE_MODE, unmarshalMetadata(metadata), 0);
      }

      return result = CacheResult.miss();
    } catch (IOException | SQLException e) {
      LOG.warn(e, "Metadata fetch(%s, %s) error", ruleKey, output);
      return result =
          CacheResult.error(
              name, CACHE_MODE, String.format("%s: %s", e.getClass(), e.getMessage()));
    } finally {
      LOG.verbose(
          "Metadata fetch(%s, %s) cache %s",
          ruleKey, output, result.getType().isSuccess() ? "hit" : "miss");
    }
  }

  @Override
  public ListenableFuture<Void> store(ArtifactInfo info, BorrowablePath content) {
    if (!getCacheReadMode().isWritable()) {
      return Futures.immediateFuture(null);
    }

    if (info.getMetadata().containsKey(TwoLevelArtifactCacheDecorator.METADATA_KEY)) {
      return Futures.transformAsync(storeMetadata(info), result -> removeOldMetadata());
    } else {
      return Futures.transformAsync(
          storeContent(info.getRuleKeys(), content), result -> removeOldContent());
    }
  }

  private ListenableFuture<Void> storeMetadata(ArtifactInfo info) {
    ImmutableMap<String, String> metadata = info.getMetadata();

    // verify that all metadata keys are valid
    for (String key : metadata.keySet()) {
      if (!BuildInfo.METADATA_KEYS.contains(key)
          && !key.equals(TwoLevelArtifactCacheDecorator.METADATA_KEY)) {
        throw new HumanReadableException("Metadata contained unexpected key: [%s]", key);
      }
    }

    try {
      db.storeMetadata(info.getRuleKeys(), marshalMetadata(metadata));
    } catch (IOException | SQLException e) {
      LOG.warn(e, "Metadata store(%s) error", info.getRuleKeys());
    }

    return Futures.immediateFuture(null);
  }

  private ListenableFuture<Void> storeContent(
      ImmutableSet<RuleKey> contentHashes, BorrowablePath content) {
    try {
      ImmutableList.Builder<RuleKey> inlinedHashes = ImmutableList.builder();
      ImmutableList.Builder<byte[]> artifacts = ImmutableList.builder();
      ImmutableList.Builder<Long> inlinedSizes = ImmutableList.builder();

      ImmutableList.Builder<RuleKey> fileHashes = ImmutableList.builder();
      ImmutableList.Builder<String> filepaths = ImmutableList.builder();
      ImmutableList.Builder<Long> fileSizes = ImmutableList.builder();

      Optional<Path> movedArtifactPath = Optional.empty();
      for (RuleKey contentHash : contentHashes) {
        // if the content already exists in the cache, skip it
        ResultSet existingArtifact = db.selectContent(contentHash);
        if (existingArtifact.next()) {
          byte[] inlined = existingArtifact.getBytes(1);
          String artifactPath = existingArtifact.getString(2);

          if (Objects.nonNull(inlined) || filesystem.exists(filesystem.resolve(artifactPath))) {
            db.accessContent(contentHash);
            continue;
          }
        }

        long size = filesystem.getFileSize(content.getPath());
        if (size <= maxInlinedBytes) {
          // artifact is small enough to inline in the database
          inlinedHashes.add(contentHash);
          artifacts.add(Files.readAllBytes(content.getPath()));
          inlinedSizes.add(size);
        } else {
          // artifact is too large to inline, store on disk and put path in database
          Path artifactPath = getArtifactPath(contentHash);
          filesystem.mkdirs(artifactPath.getParent());

          if (!content.canBorrow()) {
            storeArtifactOutput(content.getPath(), artifactPath);
          } else if (movedArtifactPath.isPresent()) {
            storeArtifactOutput(movedArtifactPath.get(), artifactPath);
          } else {
            movedArtifactPath = Optional.of(artifactPath);
            filesystem.move(content.getPath(), artifactPath, StandardCopyOption.REPLACE_EXISTING);
          }

          fileHashes.add(contentHash);
          filepaths.add(artifactPath.toString());
          fileSizes.add(size);
        }
      }

      db.storeArtifacts(inlinedHashes.build(), artifacts.build(), inlinedSizes.build());
      db.storeFilepaths(fileHashes.build(), filepaths.build(), fileSizes.build());
    } catch (IOException | SQLException e) {
      LOG.warn(e, "Artifact store(%s, %s) error", contentHashes, content);
    }

    return Futures.immediateFuture(null);
  }

  @VisibleForTesting
  static byte[] marshalMetadata(ImmutableMap<String, String> metadata) throws IOException {
    ByteArrayOutputStream metadataStream = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(metadataStream)) {
      out.writeInt(metadata.size());
      for (Map.Entry<String, String> entry : metadata.entrySet()) {
        out.writeUTF(entry.getKey());
        byte[] value = entry.getValue().getBytes(Charsets.UTF_8);
        out.writeInt(value.length);
        out.write(value);
      }
    }
    return metadataStream.toByteArray();
  }

  @VisibleForTesting
  static ImmutableMap<String, String> unmarshalMetadata(byte[] metadata) throws IOException {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(metadata))) {
      int rows = in.readInt();
      for (int i = 0; i < rows; i++) {
        String key = in.readUTF();
        int valueLength = in.readInt();
        byte[] value = new byte[valueLength];
        ByteStreams.readFully(in, value);
        builder.put(key, new String(value, Charsets.UTF_8));
      }
    }
    return builder.build();
  }

  @VisibleForTesting
  Path getArtifactPath(RuleKey ruleKey) {
    Path dir = cacheDir;

    String keyString = ruleKey.toString();
    if (keyString.length() > 4) {
      dir = dir.resolve(keyString.substring(0, 2)).resolve(keyString.substring(2, 4));
    }

    return dir.resolve(keyString);
  }

  private void storeArtifactOutput(Path content, Path cachedArtifact) throws IOException {
    // Write to a temporary file and move the file to its final location atomically to protect
    // against partial artifacts (whether due to buck interruption or filesystem failure) posing
    // as valid artifacts during subsequent buck runs.
    Path tmp = filesystem.createTempFile("artifact", TMP_EXTENSION);
    try {
      filesystem.copyFile(content, tmp);
      filesystem.move(tmp, cachedArtifact);
    } finally {
      filesystem.deleteFileAtPathIfExists(tmp);
    }
  }

  /** Removes metadata older than a computed eviction time. */
  private ListenableFuture<Void> removeOldMetadata() {
    Timestamp evictionTime = Timestamp.from(Instant.now().minus(DEFAULT_EVICTION_TIME));
    try {
      int deleted = db.deleteMetadata(evictionTime);
      LOG.verbose("Removed %d metadata rows not accessed since %s", deleted, evictionTime);
    } catch (SQLException e) {
      LOG.error(e, "Failed to clean database");
    }

    return Futures.immediateFuture(null);
  }

  /** Deletes files that haven't been accessed recently from the directory cache. */
  private ListenableFuture<Void> removeOldContent() {
    if (!maxCacheSizeBytes.isPresent()) {
      return Futures.immediateFuture(null);
    }

    long totalSizeBytes;
    try {
      totalSizeBytes = db.totalSize();
      if (totalSizeBytes <= maxCacheSizeBytes.get()) {
        return Futures.immediateFuture(null);
      }
    } catch (SQLException e) {
      LOG.error(e, "Failed to find total artifact size.");
      return Futures.immediateFuture(null);
    }

    try {
      ResultSet artifacts = db.selectContentByTime();
      while (totalSizeBytes > maxBytesAfterDeletion.get() && artifacts.next()) {
        String filepath = artifacts.getString(1);
        long sizeBytes = artifacts.getLong(2);

        // from database constraint, exactly one of filepath/artifact is null
        if (Objects.nonNull(filepath)) {
          LOG.verbose("Deleting path [%s] of total size [%d] bytes.", filepath, sizeBytes);
          Path filePathInFS = filesystem.resolve(filepath);
          MoreFiles.deleteRecursivelyIfExists(filePathInFS);
        } else {
          LOG.verbose("Deleting inlined artifact of size [%d] bytes.", sizeBytes);
        }

        totalSizeBytes -= sizeBytes;
      }

      Timestamp evictionCutoff;
      if (artifacts.next()) {
        evictionCutoff = artifacts.getTimestamp(3);
      } else {
        evictionCutoff = Timestamp.from(Instant.now());
      }

      int deleted = db.deleteContent(evictionCutoff);
      LOG.verbose("Deleted %d cached artifacts last accessed before %s", deleted, evictionCutoff);
    } catch (IOException | SQLException e) {
      LOG.error(e, "Failed to clean path [%s].", filesystem.resolve(cacheDir));
    }

    return Futures.immediateFuture(null);
  }

  @Override
  public CacheReadMode getCacheReadMode() {
    return cacheMode;
  }

  @Override
  public void close() {
    db.close();
  }

  // testing utilities

  @VisibleForTesting
  void insertMetadata(RuleKey ruleKey, ImmutableMap<String, String> metadata, Timestamp time)
      throws IOException, SQLException {
    PreparedStatement stmt =
        db.connection.prepareStatement(
            "INSERT INTO metadata (rulekey, data, accessed) VALUES (?, ?, ?)");
    stmt.setBytes(1, ConnectionInfo.getBytes(ruleKey));
    stmt.setBytes(2, marshalMetadata(metadata));
    stmt.setTimestamp(3, time);
    stmt.executeUpdate();
  }

  @VisibleForTesting
  void insertContent(RuleKey contentHash, BorrowablePath file, Timestamp time)
      throws IOException, SQLException {
    long size = filesystem.getFileSize(file.getPath());
    PreparedStatement stmt =
        db.connection.prepareStatement(
            "INSERT INTO content (sha1, artifact, filepath, size, accessed, created) "
                + "VALUES (?, ?, ?, ?, ?, ?)");

    stmt.setBytes(1, ConnectionInfo.getBytes(contentHash));
    if (size <= maxInlinedBytes) {
      stmt.setBytes(2, Files.readAllBytes(file.getPath()));
    } else {
      stmt.setString(3, file.getPath().toString());
    }
    stmt.setLong(4, size);
    stmt.setTimestamp(5, time);
    stmt.setTimestamp(6, time);
    stmt.executeUpdate();
  }

  @VisibleForTesting
  ImmutableList<RuleKey> directoryFileContentHashes() throws SQLException {
    ImmutableList.Builder<RuleKey> keys = ImmutableList.builder();
    try (ResultSet rs =
        db.connection
            .createStatement()
            .executeQuery("SELECT sha1 FROM content WHERE filepath NOTNULL")) {
      while (rs.next()) {
        keys.add(new RuleKey(HashCode.fromBytes(rs.getBytes(1))));
      }
    }
    return keys.build();
  }

  @VisibleForTesting
  ImmutableList<RuleKey> inlinedArtifactContentHashes() throws SQLException {
    ImmutableList.Builder<RuleKey> keys = ImmutableList.builder();
    try (ResultSet rs =
        db.connection
            .createStatement()
            .executeQuery("SELECT sha1 FROM content WHERE artifact NOTNULL")) {
      while (rs.next()) {
        keys.add(new RuleKey(HashCode.fromBytes(rs.getBytes(1))));
      }
    }
    return keys.build();
  }

  @VisibleForTesting
  ImmutableList<RuleKey> metadataRuleKeys() throws SQLException {
    ImmutableList.Builder<RuleKey> keys = ImmutableList.builder();
    try (ResultSet rs =
        db.connection.createStatement().executeQuery("SELECT rulekey FROM metadata")) {
      while (rs.next()) {
        keys.add(new RuleKey(HashCode.fromBytes(rs.getBytes(1))));
      }
    }
    return keys.build();
  }

  private static class ConnectionInfo {
    private final Connection connection;

    private final PreparedStatement fetchMetadata;
    private final PreparedStatement fetchContent;

    private final PreparedStatement updateMetadataTime;
    private final PreparedStatement updateContentTime;

    private final PreparedStatement storeMetadata;
    private final PreparedStatement storeArtifact;
    private final PreparedStatement storeFilepath;

    private final PreparedStatement selectContentByTime;

    private final PreparedStatement deleteMetadataBeforeCutoff;
    private final PreparedStatement deleteContentBeforeCutoff;
    private final PreparedStatement deleteContentForHash;

    private final PreparedStatement contentSize;

    private ConnectionInfo(Path cacheDir) throws SQLException {
      // date format must be set to match CURRENT_TIMESTAMP
      Properties properties = new SQLiteConfig().toProperties();
      properties.setProperty(
          SQLiteConfig.Pragma.DATE_STRING_FORMAT.pragmaName, "yyyy-MM-dd HH:mm:ss");
      connection =
          DriverManager.getConnection("jdbc:sqlite:" + cacheDir.resolve("dircache.db"), properties);
      connection.createStatement().executeUpdate("PRAGMA SYNCHRONOUS = OFF");
      connection.createStatement().executeUpdate("PRAGMA JOURNAL_MODE = WAL");

      /*
       * This cache is used for two different layers, so we use two separate databases to encode these
       * layers. The first layer is a mapping from rule key to metadata, which must include an entry
       * for the content hash (currently sha1). The second layer maps content hash to content, which
       * is either inlined for small artifacts or stored on disk for large artifacts. It is not
       * expected that rule keys map to unique content hashes, but it is expected that each content
       * hash maps to a unique artifact.
       *
       * Eventually, we hope to make this cache handle both levels directly.
       */
      connection
          .createStatement()
          .executeUpdate(
              "CREATE TABLE IF NOT EXISTS metadata "
                  // SQLite primary keys can be NULL unless specified otherwise
                  + "(rulekey BLOB PRIMARY KEY NOT NULL, "
                  + "data BLOB NOT NULL, "
                  + "accessed TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) "
                  + "WITHOUT ROWID");
      connection
          .createStatement()
          .executeUpdate(
              "CREATE TABLE IF NOT EXISTS content"
                  + "(sha1 BLOB PRIMARY KEY NOT NULL,"
                  + "artifact BLOB, filepath TEXT, "
                  + "size INTEGER, "
                  + "created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                  + "accessed TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                  + "CONSTRAINT inline CHECK (artifact NOT NULL AND filepath ISNULL "
                  + "OR artifact ISNULL AND filepath NOT NULL)) "
                  + "WITHOUT ROWID");

      fetchMetadata = connection.prepareStatement("SELECT data FROM metadata WHERE rulekey = ?");
      fetchContent =
          connection.prepareStatement(
              "SELECT artifact, filepath, size FROM content WHERE sha1 = ?");

      updateMetadataTime =
          connection.prepareStatement(
              "UPDATE metadata SET accessed = CURRENT_TIMESTAMP WHERE rulekey = ?");
      updateContentTime =
          connection.prepareStatement(
              "UPDATE content SET accessed = CURRENT_TIMESTAMP WHERE sha1 = ?");

      storeMetadata =
          connection.prepareStatement("REPLACE INTO metadata (rulekey, data) VALUES (?, ?)");
      storeArtifact =
          connection.prepareStatement(
              "INSERT INTO content (sha1, artifact, size) VALUES (?, ?, ?)");
      storeFilepath =
          connection.prepareStatement(
              "INSERT INTO content (sha1, filepath, size) VALUES (?, ?, ?)");

      selectContentByTime =
          connection.prepareStatement(
              "SELECT filepath, size, accessed FROM content ORDER BY accessed ASC, created ASC");

      deleteMetadataBeforeCutoff =
          connection.prepareStatement("DELETE FROM metadata WHERE accessed < ?");
      deleteContentBeforeCutoff =
          connection.prepareStatement("DELETE FROM content WHERE accessed < ?");
      deleteContentForHash = connection.prepareStatement("DELETE FROM content WHERE sha1 = ?");

      contentSize = connection.prepareStatement("SELECT sum(size) FROM content");

      BusyHandler.setHandler(connection, new RetryBusyHandler());
    }

    private synchronized ResultSet selectMetadata(RuleKey ruleKey) throws SQLException {
      fetchMetadata.setBytes(1, getBytes(ruleKey));
      return fetchMetadata.executeQuery();
    }

    private synchronized ResultSet selectContent(RuleKey contentHash) throws SQLException {
      fetchContent.setBytes(1, getBytes(contentHash));
      return fetchContent.executeQuery();
    }

    private synchronized void accessMetadata(RuleKey ruleKey) throws SQLException {
      updateMetadataTime.setBytes(1, getBytes(ruleKey));
      updateMetadataTime.executeUpdate();
    }

    private synchronized void accessContent(RuleKey contentHash) throws SQLException {
      updateContentTime.setBytes(1, getBytes(contentHash));
      updateContentTime.executeUpdate();
    }

    private synchronized void storeMetadata(ImmutableSet<RuleKey> ruleKeys, byte[] metadata)
        throws SQLException {
      for (RuleKey ruleKey : ruleKeys) {
        storeMetadata.setBytes(1, getBytes(ruleKey));
        storeMetadata.setBytes(2, metadata);
        storeMetadata.addBatch();
      }
      storeMetadata.executeBatch();
    }

    private synchronized void storeArtifacts(
        ImmutableList<RuleKey> hashes, ImmutableList<byte[]> artifacts, ImmutableList<Long> sizes)
        throws SQLException {
      Preconditions.checkArgument(
          hashes.size() == artifacts.size() && hashes.size() == sizes.size());

      for (int i = 0; i < hashes.size(); i++) {
        storeArtifact.setBytes(1, getBytes(hashes.get(i)));
        storeArtifact.setBytes(2, artifacts.get(i));
        storeArtifact.setLong(3, sizes.get(i));
        storeArtifact.addBatch();
      }
      storeArtifact.executeBatch();
    }

    private synchronized void storeFilepaths(
        ImmutableList<RuleKey> ruleKeys, ImmutableList<String> filepaths, ImmutableList<Long> sizes)
        throws SQLException {
      Preconditions.checkArgument(
          ruleKeys.size() == filepaths.size() && ruleKeys.size() == sizes.size());

      for (int i = 0; i < ruleKeys.size(); i++) {
        storeFilepath.setBytes(1, getBytes(ruleKeys.get(i)));
        storeFilepath.setString(2, filepaths.get(i));
        storeFilepath.setLong(3, sizes.get(i));
        storeFilepath.addBatch();
      }
      storeFilepath.executeBatch();
    }

    private synchronized int deleteMetadata(Timestamp evictionCutoff) throws SQLException {
      deleteMetadataBeforeCutoff.setTimestamp(1, evictionCutoff);
      return deleteMetadataBeforeCutoff.executeUpdate();
    }

    private synchronized int deleteContent(Timestamp evictionCutoff) throws SQLException {
      deleteContentBeforeCutoff.setTimestamp(1, evictionCutoff);
      return deleteContentBeforeCutoff.executeUpdate();
    }

    private synchronized void deleteContent(RuleKey contentHash) throws SQLException {
      deleteContentForHash.setBytes(1, getBytes(contentHash));
      deleteContentForHash.executeUpdate();
    }

    private synchronized long totalSize() throws SQLException {
      ResultSet rs = contentSize.executeQuery();
      if (!rs.next()) {
        throw new SQLException("Query failed: total size of artifacts");
      }

      return rs.getLong(1);
    }

    private synchronized ResultSet selectContentByTime() throws SQLException {
      return selectContentByTime.executeQuery();
    }

    private static byte[] getBytes(RuleKey ruleKey) {
      return ruleKey.getHashCode().asBytes();
    }

    private void close() {
      try {
        connection.close();
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
