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

package com.facebook.buck.features.project.intellij.targetinfo;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A serialization format for data that offers fast lookups on disk. This is convenient for data
 * that's too large to read entirely into memory, but which you wish to be able to quickly look up.
 * It's not designed for mutable data, it's (relatively) expensive to write, and you must write all
 * values.
 */
public class HashFile<K, V> {
  private static final byte VERSION = 1;
  // Each record in the table is an int hashcode and a long offset = 4 + 8 = 12.
  private static final int SIZE_OF_TABLE_RECORD = 12;

  // The header of the file contains a byte version number and an integer count of
  // the number of hashes.
  private static final int HEADER_SIZE = 5;

  private final Serializer<K> keySerializer;
  private final Serializer<V> valueSerializer;
  private final Path path;

  public HashFile(Serializer<K> keySerializer, Serializer<V> valueSerializer, Path path) {
    this.keySerializer = keySerializer;
    this.valueSerializer = valueSerializer;
    this.path = path;
  }

  /** Returns the path for this file. */
  public Path getPath() {
    return path;
  }

  /**
   * Writes all the data in the given map out to the file, overwriting any existing content.
   *
   * @param data data to write out.
   * @throws IOException
   */
  public void write(Map<K, V> data) throws IOException {
    Map<Integer, List<K>> hashCodeToKey = new TreeMap<>();
    for (K key : data.keySet()) {
      List<K> targets = hashCodeToKey.computeIfAbsent(key.hashCode(), k -> new ArrayList<K>(1));
      targets.add(key);
    }

    ByteArrayOutputStream headerBytes =
        new ByteArrayOutputStream(SIZE_OF_TABLE_RECORD * data.size());
    ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();

    int startOffsetForBody = HEADER_SIZE + (hashCodeToKey.size() * SIZE_OF_TABLE_RECORD);

    try (DataOutputStream body = new DataOutputStream(bodyBytes)) {
      try (DataOutputStream headerOut = new DataOutputStream(headerBytes)) {
        headerOut.writeByte(VERSION);
        headerOut.writeInt(hashCodeToKey.size());
        for (Map.Entry<Integer, List<K>> entry : hashCodeToKey.entrySet()) {
          headerOut.writeInt(entry.getKey());
          headerOut.writeLong(startOffsetForBody + body.size());

          body.writeByte(entry.getValue().size());
          for (K key : entry.getValue()) {
            keySerializer.serialize(key, body);
            V value = data.get(key);
            valueSerializer.serialize(value, body);
          }
        }
      }
    }

    try (FileOutputStream os = new FileOutputStream(path.toFile())) {
      os.write(headerBytes.toByteArray());
      os.write(bodyBytes.toByteArray());
    }
  }

  /** Gets a value, or null if the value is not present. */
  public V get(K key) throws IOException {
    int targetHash = key.hashCode();
    try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
      byte version = file.readByte();
      if (version != VERSION) {
        throw new IOException("Unexpected version in " + path + ": " + version);
      }
      // Read the number of hashes in the file.
      int hashCount = file.readInt();
      if (hashCount == 0) {
        return null;
      }

      // Binary search for the hash.
      int start = 0;
      int end = hashCount;

      long targetOffset = -1;
      while (start <= end && start >= 0) {
        int mid = start + ((end - start) / 2);

        long offset = HEADER_SIZE + (SIZE_OF_TABLE_RECORD * mid);
        file.seek(offset);
        int hash = file.readInt();

        if (hash == targetHash) {
          targetOffset = file.readLong();
          break;
        } else if (hash < targetHash) {
          start = mid + 1;
        } else {
          end = mid - 1;
        }
      }

      // Not found
      if (targetOffset == -1) {
        return null;
      }

      file.seek(targetOffset);
      byte count = file.readByte();
      for (int i = 0; i < count; i++) {
        // Read the key
        K currentKey = keySerializer.deserialize(file);

        // We need to read the whole record, because we have no idea how big it is, and
        // we have to skip past it even if the key doesn't match.
        V currentValue = valueSerializer.deserialize(file);
        if (currentKey.equals(key)) {
          return currentValue;
        }
      }
    }
    return null;
  }

  /**
   * An interface that knows how to serialize and deserialize instances of the values of a
   * particular kind.
   */
  public static interface Serializer<T> {
    void serialize(T value, DataOutput output) throws IOException;

    T deserialize(DataInput input) throws IOException;
  }

  /** An implementation of Serializer that can read and write string values. */
  public static final Serializer<String> STRING_SERIALIZER =
      new Serializer<String>() {
        @Override
        public void serialize(String value, DataOutput output) throws IOException {
          output.writeUTF(value);
        }

        @Override
        public String deserialize(DataInput input) throws IOException {
          return input.readUTF();
        }
      };
}
