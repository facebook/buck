/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.util.sha1;

import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;

import java.nio.ByteBuffer;
import java.util.regex.Pattern;

/**
 * A typesafe representation of a SHA-1 hash. It is safer to pass this around than a {@code byte[]}.
 */
public final class Sha1HashCode {

  private static final int NUM_BYTES_IN_HASH = 20;
  private static final int NUM_BYTES_IN_HEX_REPRESENTATION = 2 * NUM_BYTES_IN_HASH;

  private static final Pattern SHA1_PATTERN = Pattern.compile(
      String.format("[a-f0-9]{%d}", NUM_BYTES_IN_HEX_REPRESENTATION));

  public final int firstFourBytes;
  public final long nextEightBytes;
  public final long lastEightBytes;

  private Sha1HashCode(int firstFourBytes, long nextEightBytes, long lastEightBytes) {
    this.firstFourBytes = firstFourBytes;
    this.nextEightBytes = nextEightBytes;
    this.lastEightBytes = lastEightBytes;
  }

  /**
   * Clones the specified bytes and uses the clone to create a new {@link Sha1HashCode}.
   */
  public static Sha1HashCode fromBytes(byte[] bytes) {
    // TODO(bolinfest): Direct bit-shifting manipulations might be more efficient to avoid
    // allocating a ByteBuffer.
    Preconditions.checkArgument(bytes.length == NUM_BYTES_IN_HASH);
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    return new Sha1HashCode(buffer.getInt(), buffer.getLong(), buffer.getLong());
  }

  public static Sha1HashCode fromHashCode(HashCode hashCode) {
    // Note that hashCode.asBytes() does a clone of its internal byte[], but we cannot avoid it.
    return fromBytes(hashCode.asBytes());
  }

  public static Sha1HashCode of(String hash) {
    Preconditions.checkArgument(SHA1_PATTERN.matcher(hash).matches(),
        "Should be 40 lowercase hex chars: %s.",
        hash);
    // Note that this could be done with less memory if we created the byte[20] ourselves and
    // walked the string and converted the hex chars into bytes as we went.
    byte[] bytes = HashCode.fromString(hash).asBytes();
    return fromBytes(bytes);
  }

  /**
   * @return the hash as a 40-character string from the alphabet [a-f0-9].
   */
  public String getHash() {
    return String.format("%08x", firstFourBytes) +
        String.format("%016x", nextEightBytes) +
        String.format("%016x", lastEightBytes);
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
      return this.firstFourBytes == that.firstFourBytes &&
          this.nextEightBytes == that.nextEightBytes &&
          this.lastEightBytes == that.lastEightBytes;
    }
    return false;
  }

  @Override
  public int hashCode() {
    return firstFourBytes;
  }
}
