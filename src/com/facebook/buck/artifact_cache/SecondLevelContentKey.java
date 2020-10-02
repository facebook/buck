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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.core.util.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.BaseEncoding;

/** Content keys used in metadata attached to first-level Buck Cache objects */
@BuckStyleValueWithBuilder
public abstract class SecondLevelContentKey {
  private static final Logger LOG = Logger.get(SecondLevelContentKey.class);
  private static final BaseEncoding RULE_KEY_ENCODING = BaseEncoding.base16().lowerCase();

  private static final String OLD_STYLE_SUFFIX = "2c00";
  private static final String CACHE_ONLY_PREFIX = "cache";
  private static final String CAS_ONLY_PREFIX = "cas";
  private static final String CONTENT_KEY_SEPARATOR = "/";
  private static final String CONTENT_KEY_DIGEST_INFO_SEPARATOR = ":";

  /** Types of content keys. Can be used to send/retrieve from different backends. */
  public enum Type {
    OLD_STYLE,
    CACHE_ONLY,
    CAS_ONLY,
    UNKNOWN,
  }

  public abstract Type getType();

  public abstract String getKey();

  /**
   * Parses a given string into a SecondLevelContentKey
   *
   * @param contentKey is the raw string
   * @return the SecondLevelContentKey
   */
  public static SecondLevelContentKey fromString(String contentKey) {
    // SHA-1 hashes are 40 characters long
    if (contentKey.length() == 44 && contentKey.endsWith(OLD_STYLE_SUFFIX)) {
      return new Builder().setType(Type.OLD_STYLE).setKey(contentKey).build();
    }

    String[] parts = contentKey.split(CONTENT_KEY_SEPARATOR, 2);
    if (parts.length < 2) {
      LOG.warn("Couldn't determine type of content key (falling back to unknown): %s", contentKey);
      return new Builder().setType(Type.UNKNOWN).setKey(contentKey).build();
    }

    switch (parts[0]) {
      case CACHE_ONLY_PREFIX:
        return new Builder().setType(Type.CACHE_ONLY).setKey(parts[1]).build();
      case CAS_ONLY_PREFIX:
        return new Builder().setType(Type.CAS_ONLY).setKey(parts[1]).build();
      default:
        LOG.warn("Unknown content key prefix (falling back to unknown): %s", contentKey);
        return new Builder().setType(Type.UNKNOWN).setKey(contentKey).build();
    }
  }

  /**
   * Parses a given SecondLevelContentKey to get its digest'hash in the key
   *
   * @param contentKey a SecondLevelContentKey to parse from
   * @return the DigestHash from the key
   */
  public static String getDigestHash(SecondLevelContentKey contentKey) {
    String key = contentKey.getKey();
    // Check if this is the old style contentKey
    if (key.length() == 44 && key.endsWith(OLD_STYLE_SUFFIX)) {
      return key.substring(0, 40);
    }

    String[] parts = key.split(CONTENT_KEY_DIGEST_INFO_SEPARATOR, 2);
    return parts[0];
  }

  @VisibleForTesting
  static long getDigestBytes(SecondLevelContentKey contentKey) {
    String key = contentKey.getKey();
    // Check if this is the old-style contentKey, old-style contentKey does not have DigestBytes
    // info
    if (key.length() == 44 && key.endsWith(OLD_STYLE_SUFFIX)) {
      return 0L;
    }
    String[] parts = key.split(CONTENT_KEY_DIGEST_INFO_SEPARATOR, 2);
    if (parts.length < 2) {
      return 0L;
    }
    return Long.parseLong(parts[1]);
  }

  @Override
  public String toString() {
    switch (getType()) {
      case CACHE_ONLY:
        return CACHE_ONLY_PREFIX + CONTENT_KEY_SEPARATOR + getKey();
      case CAS_ONLY:
        return CAS_ONLY_PREFIX + CONTENT_KEY_SEPARATOR + getKey();
      case OLD_STYLE:
      case UNKNOWN:
      default:
        return getKey();
    }
  }

  /**
   * Converts the content key to a rule key, re-encoding as needed.
   *
   * @return the rule key
   */
  public RuleKey toRuleKey() {
    if (getType() == Type.OLD_STYLE) {
      return new RuleKey(toString());
    }

    return new RuleKey(RULE_KEY_ENCODING.encode(toString().getBytes()));
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableSecondLevelContentKey.Builder {}
}
