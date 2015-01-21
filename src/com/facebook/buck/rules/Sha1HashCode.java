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

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import org.immutables.value.Value;

import java.util.regex.Pattern;

/**
 * A typesafe representation of a SHA-1 hash. It is safer to pass this around than a {@link String}.
 */
@Value.Immutable
@BuckStyleImmutable
public abstract class Sha1HashCode {

  /**
   * Takes a string and uses it to construct a {@link Sha1HashCode}.
   * <p>
   * Is likely particularly useful with {@link Optional#transform(Function)}.
   */
  public static final Function<String, Sha1HashCode> TO_SHA1 =
      new Function<String, Sha1HashCode>() {
        @Override
        public Sha1HashCode apply(String hash) {
          return ImmutableSha1HashCode.of(hash);
        }
  };

  private static final Pattern SHA1_PATTERN = Pattern.compile("[a-f0-9]{40}");

  /**
   * @return the hash as a 40-character string from the alphabet [a-f0-9].
   */
  @Value.Parameter
  public abstract String getHash();

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(SHA1_PATTERN.matcher(getHash()).matches(),
        "Should be 40 lowercase hex chars: %s.",
        getHash());
  }

  public static Sha1HashCode fromHashCode(HashCode hashCode) {
    return ImmutableSha1HashCode.of(
        Hashing.sha1().newHasher().putBytes(hashCode.asBytes()).hash().toString());
  }

  /** Same as {@link #getHash()}. */
  @Override
  public String toString() {
    return getHash();
  }
}
