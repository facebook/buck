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

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import java.util.regex.Pattern;

/**
 * A typesafe representation of a SHA-1 hash. It is safer to pass this around than a {@link String}.
 */
public final class Sha1HashCode {

  private static final Pattern SHA1_PATTERN = Pattern.compile("[a-f0-9]{40}");

  private final String hash;

  /**
   * @param hash Must be a 40-character string from the alphabet [a-f0-9].
   */
  public Sha1HashCode(String hash) {
    Preconditions.checkNotNull(hash);
    Preconditions.checkArgument(SHA1_PATTERN.matcher(hash).matches(),
        "Should be 40 lowercase hex chars: %s.",
        hash);
    this.hash = hash;
  }

  /**
   * @return the hash as a 40-character string from the alphabet [a-f0-9].
   */
  public String getHash() {
    return hash;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof Sha1HashCode)) {
      return false;
    }

    Sha1HashCode that = (Sha1HashCode)obj;
    return Objects.equal(this.hash, that.hash);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(hash);
  }

  /** Same as {@link #getHash()}. */
  @Override
  public String toString() {
    return getHash();
  }
}
