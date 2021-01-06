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

package com.facebook.buck.util.sha1;

import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Pattern;

/**
 * A typesafe representation of a SHA-1 hash. It is safer to pass this around than a {@code byte[]}.
 */
public final class Sha1HashCode {

  private static final int NUM_BYTES_IN_HASH = 20;
  private static final int NUM_BYTES_IN_HEX_REPRESENTATION = 2 * NUM_BYTES_IN_HASH;

  private static final Pattern SHA1_PATTERN =
      Pattern.compile(String.format("[a-f0-9]{%d}", NUM_BYTES_IN_HEX_REPRESENTATION));

  private static final char[] HEX_DIGITS = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };

  /**
   * Because Guava's AbstractStreamingHasher uses a ByteBuffer with ByteOrder.LITTLE_ENDIAN:
   *
   * <p>https://github.com/google/guava/blob/v19.0/guava/src/com/google/common/hash/AbstractStreamingHashFunction.java#L121
   *
   * <p>and the contract for ByteBuffer#putInt(int) is:
   *
   * <p>"Writes four bytes containing the given int value, in the current byte order, into this
   * buffer at the given index."
   *
   * <p>The primitive int and long fields stored by this class must use the same ByteOrder as
   * Guava's hashing logic to facilitate the implementation of the {@link #update(Hasher)} method.
   */
  private static final ByteOrder BYTE_ORDER_FOR_FIELDS = ByteOrder.LITTLE_ENDIAN;

  // Primitive fields are used for storage so the data is stored with this class instead of on the
  // heap in a byte[].

  final int firstFourBytes;
  final long nextEightBytes;
  final long lastEightBytes;

  private Sha1HashCode(int firstFourBytes, long nextEightBytes, long lastEightBytes) {
    this.firstFourBytes = firstFourBytes;
    this.nextEightBytes = nextEightBytes;
    this.lastEightBytes = lastEightBytes;
  }

  /** Clones the specified bytes and uses the clone to create a new {@link Sha1HashCode}. */
  public static Sha1HashCode fromBytes(byte[] bytes) {
    Preconditions.checkArgument(bytes.length == NUM_BYTES_IN_HASH);
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(BYTE_ORDER_FOR_FIELDS);
    return new Sha1HashCode(buffer.getInt(), buffer.getLong(), buffer.getLong());
  }

  public static Sha1HashCode fromHashCode(HashCode hashCode) {
    // Note that hashCode.asBytes() does a clone of its internal byte[], but we cannot avoid it.
    return fromBytes(hashCode.asBytes());
  }

  public static Sha1HashCode of(String hash) {
    Preconditions.checkArgument(
        SHA1_PATTERN.matcher(hash).matches(), "Should be 40 lowercase hex chars: %s.", hash);
    // Note that this could be done with less memory if we created the byte[20] ourselves and
    // walked the string and converted the hex chars into bytes as we went.
    byte[] bytes = HashCode.fromString(hash).asBytes();
    return fromBytes(bytes);
  }

  /**
   * Updates the specified {@link Hasher} by putting the 20 bytes of this SHA-1 to it in order.
   *
   * @return The specified {@link Hasher}.
   */
  public Hasher update(Hasher hasher) {
    hasher.putInt(firstFourBytes);
    hasher.putLong(nextEightBytes);
    hasher.putLong(lastEightBytes);
    return hasher;
  }

  /**
   * <strong>This method should be used sparingly as we are trying to favor {@link Sha1HashCode}
   * over {@link HashCode}, where appropriate.</strong> Currently, the {@code FileHashCache} API is
   * written in terms of {@code HashCode}, so conversions are common. As we migrate it to use {@link
   * Sha1HashCode}, this method should become unnecessary.
   *
   * @return a {@link HashCode} with an equivalent value
   */
  public HashCode asHashCode() {
    return HashCode.fromString(getHash());
  }

  /** @return the hash as a 40-character string from the alphabet [a-f0-9]. */
  public String getHash() {
    StringBuilder sb = new StringBuilder(NUM_BYTES_IN_HEX_REPRESENTATION);
    appendInt(sb, firstFourBytes);
    appendLong(sb, nextEightBytes);
    appendLong(sb, lastEightBytes);
    return sb.toString();
  }

  private static void appendLong(StringBuilder sb, long bytes) {
    appendInt(sb, (int) bytes);
    appendInt(sb, (int) (bytes >>> 32));
  }

  private static void appendInt(StringBuilder sb, int bytes) {
    appendByte(sb, (byte) bytes);
    appendByte(sb, (byte) (bytes >>> 8));
    appendByte(sb, (byte) (bytes >>> 16));
    appendByte(sb, (byte) (bytes >>> 24));
  }

  private static void appendByte(StringBuilder sb, byte b) {
    sb.append(HEX_DIGITS[(b >>> 4) & 0xF]);
    sb.append(HEX_DIGITS[b & 0xF]);
  }

  /** Same as {@link #getHash()}. */
  @Override
  public String toString() {
    return getHash();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof Sha1HashCode) {
      Sha1HashCode that = (Sha1HashCode) obj;
      return this.firstFourBytes == that.firstFourBytes
          && this.nextEightBytes == that.nextEightBytes
          && this.lastEightBytes == that.lastEightBytes;
    }
    return false;
  }

  @Override
  public int hashCode() {
    return firstFourBytes;
  }
}
