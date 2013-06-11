/*
 * Copyright 2012-present Facebook, Inc.
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
import com.google.common.hash.Hashing;

import java.io.File;
import java.nio.ByteBuffer;

import javax.annotation.Nullable;

/**
 * OutputKey encapsulates computation of SHA-1 content hashing for outputs.
 */
public class OutputKey implements Comparable<OutputKey> {
  @Nullable private final HashCode hashCode;
  private final BuildRule buildRule;
  private final boolean idempotent;

  /**
   * OutputKeys that have no associated outputs (output == null) are treated as sentinels that are
   * distinct from other idempotent OutputKeys. OutputKeys associated with actual outputs are
   * non-idempotent if the outputs cannot be read during key generation.
   */
  public OutputKey(@Nullable File output, BuildRule buildRule) {
    this.buildRule = Preconditions.checkNotNull(buildRule);
    if (output == null) {
      this.hashCode = null;
      this.idempotent = true;
    } else {
      this.hashCode = hashOutput(output);
      this.idempotent = (hashCode != null);
    }
  }

  @Nullable
  private HashCode hashOutput(File output) {
    if (output == null) {
      return null;
    }
    // Use RuleKey's Builder to do the hard work of hashing the output file.
    RuleKey.Builder builder = RuleKey.builder(buildRule);
    builder.set(output.getPath(), output);
    RuleKey ruleKey = builder.build();
    if (!ruleKey.isIdempotent()) {
      return null;
    }
    return ruleKey.getHashCode();
  }

  public boolean isIdempotent() {
    return idempotent;
  }

  public boolean isSentinel() {
    return (isIdempotent() && hashCode == null);
  }

  /**
   * Sentinel OutputKeys are output as a string of 's' characters, and non-idempotent OutputKeys are
   * normally output as a string of 'x' characters, but when comparing two sets of OutputKeys in
   * textual form it is necessary to mangle one of the two sets, so that non-idempotent OutputKeys
   * are never considered equal.
   */
  public String toString(boolean mangleNonIdempotent) {
    if (isSentinel() || !isIdempotent()) {
      String replacementChar = isSentinel() ? "s" : (!mangleNonIdempotent ? "x" : "y");
      return new String (new char[Hashing.sha1().bits() / 4]).replace("\0", replacementChar);
    }
    return hashCode.toString();
  }

  @Override
  public String toString() {
    return toString(false);
  }

  /**
   * Order non-idempotent OutputKeys as less than sentinels, which in turn are less than all
   * idempotent non-sentinels.
   *
   * non-idempotent < sentinel < idempotent non-sentinel
   */
  @Override
  public int compareTo(OutputKey other) {
    // Handle non-idempotent keys.
    if (!isIdempotent()) {
      if (!other.isIdempotent()) {
        return 0;
      }
      return -1;
    } else if (!other.isIdempotent()) {
      return 1;
    }

    // Handle sentinel keys.
    if (isSentinel()) {
      if (other.isSentinel()) {
        return 0;
      }
      return -1;
    } else if (other.isSentinel()) {
      return 1;
    }

    // Handle idempotent non-sentinel keys.
    return ByteBuffer.wrap(hashCode.asBytes()).compareTo(ByteBuffer.wrap(other.hashCode.asBytes()));
  }

  /**
   * Treat non-idempotent OutputKeys as unequal to everything, including other non-idempotent
   * OutputKeys.
   */
  @Override
  public boolean equals(Object that) {
    if (!(that instanceof OutputKey)) {
      return false;
    }
    OutputKey other = (OutputKey) that;
    if (!isIdempotent() || !other.isIdempotent()) {
      return false;
    }
    return (compareTo(other) == 0);
  }

  @Override
  public int hashCode() {
    if (!isIdempotent()) {
      return 0;
    }
    return super.hashCode();
  }

  /**
   * Helper method used to avoid memoizing non-idempotent OutputKeys.
   */
  public static OutputKey filter(OutputKey outputKey) {
    if (!outputKey.isIdempotent()) {
      return null;
    }
    return outputKey;
  }
}
