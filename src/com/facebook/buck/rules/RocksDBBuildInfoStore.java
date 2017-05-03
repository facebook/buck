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

package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Charsets;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

public class RocksDBBuildInfoStore implements BuildInfoStore {
  private static final char KEY_SEP = '\t';

  private final RocksDB db;

  public RocksDBBuildInfoStore(ProjectFilesystem filesystem) throws IOException {
    RocksDB.loadLibrary();
    Path pathToDB =
        filesystem
            .getRootPath()
            .resolve(filesystem.getBuckPaths().getScratchDir().resolve("metadata"));
    try (Options options = new Options()) {
      options.setCreateIfMissing(true);
      db = RocksDB.open(options, pathToDB.toString());
    } catch (RocksDBException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void close() {
    db.close();
  }

  @Override
  public Optional<String> readMetadata(BuildTarget buildTarget, String key) {
    try {
      byte[] result = db.get(makeKey(buildTarget, key));
      if (result == null) {
        return Optional.empty();
      }
      return Optional.of(new String(result, Charsets.UTF_8));
    } catch (RocksDBException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void updateMetadata(BuildTarget buildTarget, Map<String, String> metadata)
      throws IOException {
    try (WriteOptions options = new WriteOptions();
        WriteBatch wb = new WriteBatch()) {
      for (Map.Entry<String, String> entry : metadata.entrySet()) {
        wb.put(makeKey(buildTarget, entry.getKey()), entry.getValue().getBytes(Charsets.UTF_8));
      }
      db.write(options, wb);
    } catch (RocksDBException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void deleteMetadata(BuildTarget buildTarget) throws IOException {
    try (WriteOptions options = new WriteOptions();
        WriteBatch wb = new WriteBatch();
        RocksIterator it = db.newIterator()) {
      byte[] keyPrefix = keyPrefix(buildTarget);
      it.seek(keyPrefix);
      while (it.isValid() && startsWith(it.key(), keyPrefix)) {
        wb.remove(it.key());
        it.next();
      }
      db.write(options, wb);
    } catch (RocksDBException e) {
      throw new IOException(e);
    }
  }

  private boolean startsWith(byte[] src, byte[] cmp) {
    if (src.length < cmp.length) {
      return false;
    }
    for (int i = 0; i < cmp.length; i++) {
      if (src[i] != cmp[i]) {
        return false;
      }
    }
    return true;
  }

  private static byte[] makeKey(BuildTarget buildTarget, String key) {
    return (buildTarget.getFullyQualifiedName() + KEY_SEP + key).getBytes(Charsets.UTF_8);
  }

  private static byte[] keyPrefix(BuildTarget buildTarget) {
    return (buildTarget.getFullyQualifiedName() + KEY_SEP).getBytes(Charsets.UTF_8);
  }
}
