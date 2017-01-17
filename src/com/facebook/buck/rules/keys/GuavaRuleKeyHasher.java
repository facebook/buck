/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourceRoot;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * An implementation of {@link RuleKeyHasher} that wraps Guava's {@link Hasher}.
 */
public class GuavaRuleKeyHasher implements RuleKeyHasher<HashCode> {
  // Key
  private static final byte TYPE_KEY = 'k';
  // Java types
  private static final byte TYPE_NULL = '0';
  private static final byte TYPE_TRUE = 'y';
  private static final byte TYPE_FALSE = 'n';
  private static final byte TYPE_BYTE_ARRAY = 'a';
  private static final byte TYPE_BYTE = 'b';
  private static final byte TYPE_SHORT = 'h';
  private static final byte TYPE_INTEGER = 'i';
  private static final byte TYPE_LONG = 'l';
  private static final byte TYPE_FLOAT = 'f';
  private static final byte TYPE_DOUBLE = 'd';
  private static final byte TYPE_STRING = 's';
  private static final byte TYPE_PATTERN = 'p';
  // Buck types
  private static final byte TYPE_SHA1 = 'H';
  private static final byte TYPE_PATH = 'P';
  private static final byte TYPE_ARCHIVE_MEMBER_PATH = 'A';
  private static final byte TYPE_NON_HASHING_PATH = 'N';
  private static final byte TYPE_SOURCE_ROOT = 'R';
  private static final byte TYPE_RULE_KEY = 'K';
  private static final byte TYPE_RULE_TYPE = 'Y';
  private static final byte TYPE_TARGET = 'T';
  private static final byte TYPE_TARGET_SOURCE_PATH = 'S';
  // Containers
  private static final byte TYPE_CONTAINER = 'C';
  private static final byte TYPE_WRAPPER = 'W';

  private final Hasher hasher;

  public GuavaRuleKeyHasher(Hasher hasher) {
    this.hasher = hasher;
  }

  private RuleKeyHasher<HashCode> putStringified(byte type, CharSequence val) {
    hasher.putUnencodedChars(val);
    hasher.putInt(val.length());
    hasher.putByte(type);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putKey(String key) {
    return this.putStringified(TYPE_KEY, key);
  }

  @Override
  public RuleKeyHasher<HashCode> putNull() {
    hasher.putByte(TYPE_NULL);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putBoolean(boolean val) {
    hasher.putByte(val ? TYPE_TRUE : TYPE_FALSE);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putNumber(Number val) {
    if (val instanceof Integer) { // most common, so test first
      hasher.putInt((Integer) val);
      hasher.putByte(TYPE_INTEGER);
    } else if (val instanceof Long) {
      hasher.putLong((Long) val);
      hasher.putByte(TYPE_LONG);
    } else if (val instanceof Short) {
      hasher.putShort((Short) val);
      hasher.putByte(TYPE_SHORT);
    } else if (val instanceof Byte) {
      hasher.putByte((Byte) val);
      hasher.putByte(TYPE_BYTE);
    } else if (val instanceof Float) {
      hasher.putFloat((Float) val);
      hasher.putByte(TYPE_FLOAT);
    } else if (val instanceof Double) {
      hasher.putDouble((Double) val);
      hasher.putByte(TYPE_DOUBLE);
    } else {
      throw new UnsupportedOperationException(("Unsupported Number type: " + val.getClass()));
    }
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putString(CharSequence val) {
    return this.putStringified(TYPE_STRING, val);
  }

  @Override
  public RuleKeyHasher<HashCode> putBytes(byte[] bytes) {
    hasher.putBytes(bytes);
    hasher.putInt(bytes.length);
    hasher.putByte(TYPE_BYTE_ARRAY);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putPattern(Pattern pattern) {
    return this.putStringified(TYPE_PATTERN, pattern.toString());
  }

  @Override
  public RuleKeyHasher<HashCode> putSha1(Sha1HashCode sha1) {
    sha1.update(hasher);
    hasher.putByte(TYPE_SHA1);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putPath(Path path, String hash) {
    this.putStringified(TYPE_PATH, path.toString());
    this.putStringified(TYPE_PATH, hash);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putArchiveMemberPath(ArchiveMemberPath path, String hash) {
    this.putStringified(TYPE_ARCHIVE_MEMBER_PATH, path.toString());
    this.putStringified(TYPE_ARCHIVE_MEMBER_PATH, hash);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putNonHashingPath(String path) {
    return this.putStringified(TYPE_NON_HASHING_PATH, path);
  }

  @Override
  public RuleKeyHasher<HashCode> putSourceRoot(SourceRoot sourceRoot) {
    return this.putStringified(TYPE_SOURCE_ROOT, sourceRoot.getName());
  }

  @Override
  public RuleKeyHasher<HashCode> putRuleKey(RuleKey ruleKey) {
    return this.putStringified(TYPE_RULE_KEY, ruleKey.toString());
  }

  @Override
  public RuleKeyHasher<HashCode> putBuildRuleType(BuildRuleType buildRuleType) {
    return this.putStringified(TYPE_RULE_TYPE, buildRuleType.toString());
  }

  @Override
  public RuleKeyHasher<HashCode> putBuildTarget(BuildTarget buildTarget) {
    return this.putStringified(TYPE_TARGET, buildTarget.getFullyQualifiedName());
  }

  @Override
  public RuleKeyHasher<HashCode> putBuildTargetSourcePath(BuildTargetSourcePath targetSourcePath) {
    return this.putStringified(TYPE_TARGET_SOURCE_PATH, targetSourcePath.toString());
  }

  @Override
  public RuleKeyHasher<HashCode> putContainer(Container container, int length) {
    switch (container) {
      case LIST:
        hasher.putByte((byte) '[');
        break;
      case MAP:
        hasher.putByte((byte) '{');
        break;
      default:
        throw new UnsupportedOperationException("Unrecognized container type: " + container);
    }
    hasher.putInt(length);
    hasher.putByte(TYPE_CONTAINER);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putWrapper(Wrapper wrapper) {
    switch (wrapper) {
      case SUPPLIER:
        hasher.putByte((byte) 'S');
        break;
      case OPTIONAL:
        hasher.putByte((byte) 'O');
        break;
      case EITHER_LEFT:
        hasher.putByte((byte) 'L');
        break;
      case EITHER_RIGHT:
        hasher.putByte((byte) 'R');
        break;
      case BUILD_RULE:
        hasher.putByte((byte) 'B');
        break;
      case APPENDABLE:
        hasher.putByte((byte) 'A');
        break;
      default:
        throw new UnsupportedOperationException("Unrecognized wrapper type: " + wrapper);
    }
    hasher.putByte(TYPE_WRAPPER);
    return this;
  }

  @Override
  public HashCode hash() {
    return hasher.hash();
  }
}
