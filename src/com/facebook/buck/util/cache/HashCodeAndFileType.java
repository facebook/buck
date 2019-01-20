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

package com.facebook.buck.util.cache;

import com.google.common.hash.HashCode;
import java.util.Objects;
import javax.annotation.Nullable;

/** Data container to hold hash value for a file or directory */
public class HashCodeAndFileType {
  private final byte type;
  private final HashCode hashCode;

  protected HashCodeAndFileType(byte type, HashCode hashCode) {
    this.type = type;
    this.hashCode = hashCode;
  }

  /** @return type of the file as a constant; can be TYPE_DIRECTORY, TYPE_FILE or TYPE_ARCHIVE */
  public byte getType() {
    return type;
  }

  /** @return Hash value of the file or directory */
  public HashCode getHashCode() {
    return hashCode;
  }

  /**
   * This instance is equal to all instances of {@code HashCodeAndFileType} that have equal
   * attribute values.
   *
   * @return {@code true} if {@code this} is equal to {@code another} instance
   */
  @Override
  public boolean equals(@Nullable Object another) {
    if (this == another) return true;
    return another instanceof HashCodeAndFileType && equalTo((HashCodeAndFileType) another);
  }

  private boolean equalTo(HashCodeAndFileType another) {
    return type == another.type && hashCode.equals(another.hashCode);
  }

  /**
   * Computes a hash code from attributes: {@code type}, {@code hashCode}.
   *
   * @return hashCode value
   */
  @Override
  public int hashCode() {
    return Objects.hash(type, hashCode);
  }

  public static HashCodeAndFileType ofDirectory(HashCode hashCode) {
    return new HashCodeAndFileType(TYPE_DIRECTORY, hashCode);
  }

  public static HashCodeAndFileType ofFile(HashCode hashCode) {
    return new HashCodeAndFileType(TYPE_FILE, hashCode);
  }

  // Using byte consts instead of enums to optimize memory footprint
  public static final byte TYPE_DIRECTORY = 0;
  public static final byte TYPE_FILE = 1;
  public static final byte TYPE_ARCHIVE = 2;
}
