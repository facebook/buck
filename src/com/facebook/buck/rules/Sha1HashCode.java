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

package com.facebook.buck.rules;

import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;

import java.util.regex.Pattern;

/**
 * A typesafe representation of a SHA-1 hash. It is safer to pass this around than a {@code byte[]}.
 */
public final class Sha1HashCode {

  private static final int NUM_BYTES_IN_HASH = 20;
  private static final int NUM_BYTES_IN_HEX_REPRESENTATION = 2 * NUM_BYTES_IN_HASH;

  private static final Pattern SHA1_PATTERN = Pattern.compile(
      String.format("[a-f0-9]{%d}", NUM_BYTES_IN_HEX_REPRESENTATION));

  private final byte[] bytes;

  /**
   * @param bytes may not be modified once it is passed to this constructor. If the caller cannot
   *     guarantee this, then the caller is responsible for passing {@code bytes.clone()} instead of
   *     {@code bytes}.
   */
  private Sha1HashCode(byte[] bytes) {
    Preconditions.checkArgument(
        bytes.length == NUM_BYTES_IN_HASH,
        "Length of bytes must be %d but was %d.",
        NUM_BYTES_IN_HASH,
        bytes.length);
    this.bytes = bytes;
  }

  /**
   * Clones the specified bytes and uses the clone to create a new {@link Sha1HashCode}.
   */
  public static Sha1HashCode fromBytes(byte[] bytes) {
    // TODO(bolinfest): Investigate the performance impact of skipping this clone.
    return new Sha1HashCode(bytes.clone());
  }

  public static Sha1HashCode fromHashCode(HashCode hashCode) {
    // Note that hashCode.asBytes() does a clone of its internal byte[], but we cannot avoid it.
    return new Sha1HashCode(hashCode.asBytes());
  }

  public static Sha1HashCode of(String hash) {
    Preconditions.checkArgument(SHA1_PATTERN.matcher(hash).matches(),
        "Should be 40 lowercase hex chars: %s.",
        hash);
    // Note that this could be done with less memory if we created the byte[20] ourselves and
    // walked the string and converted the hex chars into bytes as we went.
    byte[] bytes = HashCode.fromString(hash).asBytes();
    return new Sha1HashCode(bytes);
  }

  /**
   * @return a fresh copy of the internal bytes
   */
  byte[] getBytes() {
    // TODO(bolinfest): Investigate the performance impact of skipping this clone.
    return bytes.clone();
  }

  /**
   * @return the hash as a 40-character string from the alphabet [a-f0-9].
   */
  public String getHash() {
    return HashCode.fromBytes(bytes).toString();
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
      for (int i = 0; i < NUM_BYTES_IN_HASH; i++) {
        if (this.bytes[i] != that.bytes[i]) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  @Override
  public int hashCode() {
    return
        ((bytes[0] & 0xff) << 24) |
        ((bytes[1] & 0xff) << 16) |
        ((bytes[2] & 0xff) <<  8) |
        ((bytes[3] & 0xff));
  }
}
