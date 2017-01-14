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

package com.facebook.buck.rules;

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * An implementation of {@link RuleKeyHasher} that wraps Guava's {@link Hasher}.
 */
public class GuavaRuleKeyHasher implements RuleKeyHasher<HashCode> {
  // TODO(plamenko): this is the old way of delimiting chunks, which is not the proper way of
  // doing it. This is currently kept as is to preserve the old behavior, but this should get
  // converted to length based delimiting.
  private static final byte SEPARATOR = '\0';

  private final Hasher hasher;

  public GuavaRuleKeyHasher(Hasher hasher) {
    this.hasher = hasher;
  }

  @Override
  public RuleKeyHasher<HashCode> putKey(String val) {
    this.putString(val);
    // already delimited
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putNull() {
    hasher.putBytes(new byte[0]);
    hasher.putByte(SEPARATOR);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putBoolean(boolean val) {
    hasher.putBoolean(val);
    hasher.putByte(SEPARATOR);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putNumber(Number val) {
    if (val instanceof Integer) { // most common, so test first
      hasher.putInt((Integer) val);
    } else if (val instanceof Long) {
      hasher.putLong((Long) val);
    } else if (val instanceof Short) {
      hasher.putShort((Short) val);
    } else if (val instanceof Byte) {
      hasher.putByte((Byte) val);
    } else if (val instanceof Float) {
      hasher.putFloat((Float) val);
    } else if (val instanceof Double) {
      hasher.putDouble((Double) val);
    } else {
      throw new UnsupportedOperationException(("Unsupported Number type: " + val.getClass()));
    }
    hasher.putByte(SEPARATOR);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putString(CharSequence val) {
    hasher.putUnencodedChars(val);
    hasher.putByte(SEPARATOR);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putBytes(byte[] bytes) {
    hasher.putBytes(bytes);
    hasher.putByte(SEPARATOR);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putPattern(Pattern pattern) {
    this.putString(pattern.toString());
    // already delimited
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putSha1(Sha1HashCode sha1) {
    sha1.update(hasher);
    hasher.putByte(SEPARATOR);
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putPath(Path path, String hash) {
    this.putString(path.toString());
    this.putString(hash);
    // already delimited
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putArchiveMemberPath(ArchiveMemberPath path, String hash) {
    this.putString(path.toString());
    this.putString(hash);
    // already delimited
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putNonHashingPath(String path) {
    this.putString(path);
    // already delimited
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putSourceRoot(SourceRoot sourceRoot) {
    this.putString(sourceRoot.getName());
    // already delimited
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putRuleKey(RuleKey ruleKey) {
    this.putString(ruleKey.toString());
    // already delimited
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putBuildRuleType(BuildRuleType buildRuleType) {
    this.putString(buildRuleType.toString());
    // already delimited
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putBuildTarget(BuildTarget buildTarget) {
    this.putString(buildTarget.getFullyQualifiedName());
    // already delimited
    return this;
  }

  @Override
  public RuleKeyHasher<HashCode> putBuildTargetSourcePath(BuildTargetSourcePath targetSourcePath) {
    this.putString(targetSourcePath.toString());
    // already delimited
    return this;
  }

  @Override
  public HashCode hash() {
    return hasher.hash();
  }
}
