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

package com.facebook.buck.rules.keys.hasher;

import com.facebook.buck.core.io.ArchiveMemberPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** An implementation of {@link RuleKeyHasher} that wraps Guava's {@link Hasher}. */
public class GuavaRuleKeyHasher implements RuleKeyHasher<HashCode> {

  private final Hasher hasher;

  public GuavaRuleKeyHasher(Hasher hasher) {
    this.hasher = hasher;
  }

  private GuavaRuleKeyHasher putBytes(byte type, byte[] bytes) {
    hasher.putBytes(bytes);
    hasher.putInt(bytes.length);
    hasher.putByte(type);
    return this;
  }

  private GuavaRuleKeyHasher putStringified(byte type, String val) {
    return putBytes(type, val.getBytes(StandardCharsets.UTF_8));
  }

  private GuavaRuleKeyHasher putBuildTarget(byte type, BuildTarget target) {
    return putStringified(type, target.getFullyQualifiedName());
  }

  @Override
  public GuavaRuleKeyHasher putKey(String key) {
    return this.putStringified(RuleKeyHasherTypes.KEY, key);
  }

  @Override
  public GuavaRuleKeyHasher putNull() {
    hasher.putByte(RuleKeyHasherTypes.NULL);
    return this;
  }

  @Override
  public GuavaRuleKeyHasher putCharacter(char val) {
    hasher.putChar(val);
    return this;
  }

  @Override
  public GuavaRuleKeyHasher putBoolean(boolean val) {
    hasher.putByte(val ? RuleKeyHasherTypes.TRUE : RuleKeyHasherTypes.FALSE);
    return this;
  }

  @Override
  public GuavaRuleKeyHasher putNumber(Number val) {
    if (val instanceof Integer) { // most common, so test first
      hasher.putInt((Integer) val);
      hasher.putByte(RuleKeyHasherTypes.INTEGER);
    } else if (val instanceof Long) {
      hasher.putLong((Long) val);
      hasher.putByte(RuleKeyHasherTypes.LONG);
    } else if (val instanceof Short) {
      hasher.putShort((Short) val);
      hasher.putByte(RuleKeyHasherTypes.SHORT);
    } else if (val instanceof Byte) {
      hasher.putByte((Byte) val);
      hasher.putByte(RuleKeyHasherTypes.BYTE);
    } else if (val instanceof Float) {
      hasher.putFloat((Float) val);
      hasher.putByte(RuleKeyHasherTypes.FLOAT);
    } else if (val instanceof Double) {
      hasher.putDouble((Double) val);
      hasher.putByte(RuleKeyHasherTypes.DOUBLE);
    } else {
      throw new UnsupportedOperationException(("Unsupported Number type: " + val.getClass()));
    }
    return this;
  }

  @Override
  public GuavaRuleKeyHasher putString(String val) {
    return this.putStringified(RuleKeyHasherTypes.STRING, val);
  }

  @Override
  public GuavaRuleKeyHasher putBytes(byte[] bytes) {
    return putBytes(RuleKeyHasherTypes.BYTE_ARRAY, bytes);
  }

  @Override
  public GuavaRuleKeyHasher putPattern(Pattern pattern) {
    return this.putStringified(RuleKeyHasherTypes.PATTERN, pattern.toString());
  }

  @Override
  public GuavaRuleKeyHasher putSha1(Sha1HashCode sha1) {
    sha1.update(hasher);
    hasher.putByte(RuleKeyHasherTypes.SHA1);
    return this;
  }

  @Override
  public GuavaRuleKeyHasher putPath(Path path, HashCode hash) {
    this.putStringified(RuleKeyHasherTypes.PATH, path.toString());
    this.putBytes(RuleKeyHasherTypes.PATH, hash.asBytes());
    return this;
  }

  @Override
  public GuavaRuleKeyHasher putArchiveMemberPath(ArchiveMemberPath path, HashCode hash) {
    this.putStringified(RuleKeyHasherTypes.ARCHIVE_MEMBER_PATH, path.toString());
    this.putBytes(RuleKeyHasherTypes.ARCHIVE_MEMBER_PATH, hash.asBytes());
    return this;
  }

  @Override
  public GuavaRuleKeyHasher putNonHashingPath(String path) {
    return this.putStringified(RuleKeyHasherTypes.NON_HASHING_PATH, path);
  }

  @Override
  public GuavaRuleKeyHasher putRuleKey(RuleKey ruleKey) {
    return this.putBytes(RuleKeyHasherTypes.RULE_KEY, ruleKey.getHashCode().asBytes());
  }

  @Override
  public GuavaRuleKeyHasher putRuleType(RuleType ruleType) {
    return this.putStringified(RuleKeyHasherTypes.RULE_TYPE, ruleType.toString());
  }

  @Override
  public GuavaRuleKeyHasher putBuildTarget(BuildTarget buildTarget) {
    return this.putStringified(RuleKeyHasherTypes.TARGET, buildTarget.getFullyQualifiedName());
  }

  @Override
  public RuleKeyHasher<HashCode> putBuildTargetSourcePath(BuildTargetSourcePath targetSourcePath) {
    this.putBuildTarget(RuleKeyHasherTypes.TARGET_SOURCE_PATH, targetSourcePath.getTarget());
    if (targetSourcePath instanceof ExplicitBuildTargetSourcePath) {
      this.putStringified(
          RuleKeyHasherTypes.TARGET_SOURCE_PATH,
          ((ExplicitBuildTargetSourcePath) targetSourcePath).getResolvedPath().toString());
    } else if (targetSourcePath instanceof ForwardingBuildTargetSourcePath) {
      this.putStringified(
          RuleKeyHasherTypes.TARGET_SOURCE_PATH, targetSourcePath.representationForRuleKey());
    }
    return this;
  }

  @Override
  public GuavaRuleKeyHasher putContainer(Container container, int length) {
    hasher.putByte(RuleKeyHasherTypes.containerSubType(container));
    hasher.putInt(length);
    hasher.putByte(RuleKeyHasherTypes.CONTAINER);
    return this;
  }

  @Override
  public GuavaRuleKeyHasher putWrapper(Wrapper wrapper) {
    hasher.putByte(RuleKeyHasherTypes.wrapperSubType(wrapper));
    hasher.putByte(RuleKeyHasherTypes.WRAPPER);
    return this;
  }

  @Override
  public HashCode hash() {
    return hasher.hash();
  }
}
