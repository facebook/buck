/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple.clang;

import com.facebook.buck.io.file.MorePaths;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Header maps are essentially hash maps from strings to paths (coded as two strings: a prefix and a
 * suffix).
 *
 * <p>This class provides support for reading and generating clang header maps. No spec is available
 * but we conform to the <a href="http://clang.llvm.org/doxygen/HeaderMap_8h_source.html">reader
 * class defined in the Clang documentation</a>.
 *
 * <p>Note: currently we don't support offsets greater than MAX_SIGNED_INT.
 */
public class HeaderMap {
  private static final double MAX_LOAD_FACTOR = 0.75;

  /**
   * Bucket in the hashtable that is a {@link HeaderMap}. Note: This notion of bucket is slightly
   * more abstract than the one on disk (string offsets being already swapped/shifted).
   */
  private static class Bucket {
    /** Offset of the key string into stringBytes. */
    final int key;

    /** Offset of the value prefix into stringBytes. */
    final int prefix;

    /** Offset of the value suffix into stringBytes. */
    final int suffix;

    Bucket(int key, int prefix, int suffix) {
      this.key = key;
      this.prefix = prefix;
      this.suffix = suffix;
    }
  }

  private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

  // NB: Despite this comment, which comes from clang, we are using this field to represent the
  // number of entries, not the number of strings. Clang doesn't seem to care about this, and xcode
  // seems to treat this as the number of entries as well.
  /** Number of entries in the string table. */
  private final int numEntries;

  /** Length of longest result path (excluding {@code null}). */
  private final int maxValueLength;

  // data containers
  private final Bucket[] buckets;
  private final byte[] stringBytes; // actually chars to make debugging easier

  private HeaderMap(Bucket[] buckets, byte[] stringTable, int numEntries, int maxValueLength) {
    Preconditions.checkArgument(buckets.length > 0, "The number of buckets must be greater than 0");
    Preconditions.checkArgument(
        (buckets.length & (buckets.length - 1)) == 0, "The number of buckets must be a power of 2");

    this.buckets = buckets;
    this.stringBytes = stringTable;
    this.numEntries = numEntries;
    this.maxValueLength = maxValueLength;
  }

  public int getNumEntries() {
    return numEntries;
  }

  public int getNumBuckets() {
    return buckets.length;
  }

  public int getMaxValueLength() {
    return maxValueLength;
  }

  /** Visitor function for {@link #visit(HeaderMapVisitor)}. */
  @FunctionalInterface
  public interface HeaderMapVisitor {
    void apply(String str, String prefix, String suffix);
  }

  public void visit(HeaderMapVisitor visitor) {
    for (Bucket bucket : buckets) {
      if (bucket != null) {
        visitor.apply(
            Objects.requireNonNull(getString(bucket.key)),
            Objects.requireNonNull(getString(bucket.prefix)),
            Objects.requireNonNull(getString(bucket.suffix)));
      }
    }
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    print(builder);
    return builder.toString();
  }

  public void print(Appendable stream) {
    visit(
        (str, prefix, suffix) -> {
          try {
            stream.append("\"");
            stream.append(str);
            stream.append("\" -> \"");
            stream.append(prefix);
            stream.append(suffix);
            stream.append("\"\n");
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Nullable
  public String lookup(String str) {
    int hash0 = hashKey(str) & (buckets.length - 1);

    int hash = hash0;
    while (true) {
      Bucket bucket = buckets[hash];
      if (bucket == null) {
        return null;
      }
      if (str.equalsIgnoreCase(getString(bucket.key))) {
        return getString(bucket.prefix) + getString(bucket.suffix);
      }

      hash = (hash + 1) & (buckets.length - 1);
      if (hash == hash0) {
        return null;
      }
    }
  }

  // --------- I/O methods ----------

  private static final int HEADER_MAGIC = ('h' << 24) | ('m' << 16) | ('a' << 8) | 'p';
  private static final short HEADER_VERSION = 1;
  private static final short HEADER_RESERVED = 0;
  private static final int EMPTY_BUCKET_KEY = 0;

  private static final int HEADER_SIZE = 24;
  private static final int BUCKET_SIZE = 12;

  @Nullable
  public static HeaderMap deserialize(byte[] bytes) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    return deserialize(buffer);
  }

  @Nullable
  public static HeaderMap deserialize(ByteBuffer buffer) {
    return processBuffer(buffer);
  }

  public static HeaderMap loadFromFile(File hmapFile) throws IOException {
    HeaderMap map;
    try (FileInputStream inputStream = new FileInputStream(hmapFile)) {
      FileChannel fileChannel = inputStream.getChannel();
      ByteBuffer buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, hmapFile.length());

      map = HeaderMap.deserialize(buffer);
      if (map == null) {
        throw new IOException("Error while parsing header map " + hmapFile);
      }
    }
    return map;
  }

  @Nullable
  private static HeaderMap processBuffer(ByteBuffer buffer) {
    buffer.order(ByteOrder.BIG_ENDIAN);
    if (buffer.getInt(0) != HEADER_MAGIC) {
      buffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    int magic = buffer.getInt();
    short version = buffer.getShort();
    if (magic != HEADER_MAGIC || version != HEADER_VERSION) {
      return null;
    }

    /* reserved */ buffer.getShort();
    int stringOffset = buffer.getInt();
    int numEntries = buffer.getInt();
    int numBuckets = buffer.getInt();
    int maxValueLength = buffer.getInt();

    // strings can be anywhere after the end of the buckets
    int stringBytesActualLength = buffer.capacity() - HEADER_SIZE - numBuckets * BUCKET_SIZE;

    Bucket[] buckets = new Bucket[numBuckets];
    int actualOffset = HEADER_SIZE + numBuckets * BUCKET_SIZE - stringOffset;
    // actualOffset should always be positive since the index EMPTY_BUCKET_KEY=0 is reserved

    for (int i = 0; i < numBuckets; i++) {
      int keyRawOffset = buffer.getInt();
      int prefixRawOffset = buffer.getInt();
      int suffixRawOffset = buffer.getInt();

      if (keyRawOffset == EMPTY_BUCKET_KEY) {
        buckets[i] = null;
      } else {
        // we can subtract 1 value because index 0 is EMPTY_BUCKET_KEY
        buckets[i] =
            new Bucket(
                keyRawOffset - actualOffset,
                prefixRawOffset - actualOffset,
                suffixRawOffset - actualOffset);
      }
    }
    // anything else is string
    byte[] stringBytes = new byte[stringBytesActualLength];
    buffer.get(stringBytes);

    return new HeaderMap(buckets, stringBytes, numEntries, maxValueLength);
  }

  public int getRequiredBufferCapacity() {
    return HEADER_SIZE + buckets.length * BUCKET_SIZE + stringBytes.length;
  }

  public byte[] getBytes() {
    return getBytes(ByteOrder.BIG_ENDIAN);
  }

  public byte[] getBytes(ByteOrder bo) {
    byte[] bytes = new byte[getRequiredBufferCapacity()];
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(bo);
    serialize(buffer);
    return bytes;
  }

  public void serialize(ByteBuffer buffer) {
    int actualOffset = 1; // must be greater than EMPTY_BUCKET_KEY

    buffer.putInt(HEADER_MAGIC);
    buffer.putShort(HEADER_VERSION);
    buffer.putShort(HEADER_RESERVED);
    buffer.putInt(HEADER_SIZE + buckets.length * BUCKET_SIZE - actualOffset);
    buffer.putInt(numEntries);
    buffer.putInt(buckets.length);
    buffer.putInt(maxValueLength);

    for (Bucket bucket : buckets) {
      if (bucket == null) {
        buffer.putInt(EMPTY_BUCKET_KEY);
        buffer.putInt(0);
        buffer.putInt(0);
      } else {
        buffer.putInt(bucket.key + actualOffset);
        buffer.putInt(bucket.prefix + actualOffset);
        buffer.putInt(bucket.suffix + actualOffset);
      }
    }
    buffer.put(stringBytes);
  }

  // -------------- Builder class ----------------

  public static Builder builder() {
    return new Builder();
  }

  /** Build a header map from individual mappings. */
  @NotThreadSafe
  public static class Builder {

    static final int DEFAULT_NUM_BUCKETS = 256;

    private final Map<String, Bucket> entries = new HashMap<>();
    /** Strings to their offset in the string table. */
    private final Map<String, Integer> addedStrings = new HashMap<>();
    /** Accumulator for the string table, which holds strings delimited by null bytes. */
    private final ByteArrayOutputStream stringTable = new ByteArrayOutputStream();

    private int maxValueLength = 0;

    /** Add a mapping from include directive to path. */
    public boolean add(String key, Path path) {
      int oldSize = entries.size();
      entries.computeIfAbsent(
          Ascii.toLowerCase(key),
          _lowercaseKey -> {
            String[] parts = splitPath(path);
            maxValueLength = Math.max(maxValueLength, parts[0].length() + parts[1].length());
            return new Bucket(addString(key), addString(parts[0]), addString(parts[1]));
          });
      return oldSize != entries.size();
    }

    /** Build the header map. */
    public HeaderMap build() {
      long numBucketsL =
          Math.max(
              DEFAULT_NUM_BUCKETS,
              roundUpToNextPowerOf2((long) Math.ceil(entries.size() / MAX_LOAD_FACTOR)));
      Preconditions.checkState(
          Integer.MAX_VALUE > numBucketsL, "narrowing cast should not overflow");
      int numBuckets = (int) numBucketsL;

      Bucket[] buckets = new Bucket[numBuckets];
      entries.forEach(
          (key, bucket) -> {
            final int hash0 = hashKey(key) & (numBuckets - 1);
            int hash = hash0;
            do {
              Bucket bucketAtPoint = buckets[hash];
              if (bucketAtPoint == null) {
                buckets[hash] = bucket;
                return;
              }

              // Resolve collisions via linear probing. This is defined by the header map format.
              hash = (hash + 1) & (numBuckets - 1);
            } while (hash != hash0);
            throw new IllegalStateException(
                "Buckets are all filled, we should have allocated enough for all entries.");
          });

      return new HeaderMap(buckets, stringTable.toByteArray(), entries.size(), maxValueLength);
    }

    @VisibleForTesting
    static String[] splitPath(Path path) {
      String[] result = new String[2];
      if (path.getNameCount() < 2) {
        result[0] = "";
        result[1] = path.toString();
      } else {
        result[0] = MorePaths.pathWithUnixSeparators(path.getParent()) + "/";
        result[1] = path.getFileName().toString();
      }
      return result;
    }

    /** Write a string to the string table, or look up its existing offset. */
    private int addString(String str) {
      Integer existingOffset = addedStrings.get(str);
      if (existingOffset != null) {
        return existingOffset;
      }

      int offset = stringTable.size(); // NOPMD declaration unreferenced before possible exit.
      try {
        stringTable.write(str.getBytes(DEFAULT_CHARSET));
      } catch (IOException e) {
        throw new IllegalStateException("ByteArrayOutputStream should not throw IOExceptions.", e);
      }
      stringTable.write(0);

      addedStrings.put(str, offset);
      return offset;
    }

    private static long roundUpToNextPowerOf2(long value) {
      Preconditions.checkArgument(value >= 0);
      return (value & (value - 1)) == 0
          ? value // Already a power of 2.
          : Long.highestOneBit(value) << 1; // Get the next power of 2.
    }
  }

  // ------------- Internals -----------

  private static int hashKey(String str) {
    // ASCII lowercase is part of the format.
    // UTF8 is the standard filesystem charset.
    return hashKey(Ascii.toLowerCase(str).getBytes(DEFAULT_CHARSET));
  }

  private static int hashKey(byte[] str) {
    int key = 0;
    for (byte c : str) {
      key += c * 13;
    }
    return key;
  }

  @Nullable
  private String getString(int offset) {
    Preconditions.checkArgument(offset >= 0 && offset <= stringBytes.length);

    StringBuilder builder = new StringBuilder();
    byte b;
    while ((offset < stringBytes.length) && ((b = stringBytes[offset]) != 0)) {
      builder.append((char) b);
      offset++;
    }

    if (offset == stringBytes.length) {
      // We reached the end of the array without finding a 0.
      return null;
    }
    return builder.toString();
  }
}
