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
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.ForwardingBuildTargetSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyFieldCategory;
import com.facebook.buck.rules.SourceRoot;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * An implementation of {@link RuleKeyHasher} that uses a 32bit polynomial hash.
 *
 * This implementation doesn't satisfy strong collision resistance properties but it is fast and
 * occupy only 32bits which is handy for instrumentation purposes.
 */
public class PolynomialRuleKeyHasher implements RuleKeyHasher<Integer> {

  private int hash = 0;

  private void feed(int val) {
    hash = hash * 33 + val;
  }

  private void feedLong(long val) {
    feed((int) (val >> 32));
    feed((int) val);
  }

  private void feedBytes(byte[] bytes) {
    for (int i = 0; i < bytes.length; i++) {
      feed(bytes[i]);
    }
  }

  private void feedString(String val) {
    // We could iterate over chars manually, but {@link String#hashCode()} is already computed as
    // a polynomial hash, looks stable enough as per Java specification, and is memoized which
    // means it's going to be computed only once if attempted multiple times.
    // See http://docs.oracle.com/javase/8/docs/api/java/lang/String.html#hashCode--
    feed(val.hashCode());
  }

  @Override
  public RuleKeyHasher<Integer> selectCategory(RuleKeyFieldCategory category) {
    // Category is handled outside of this class so it can be safely ignored here.
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putKey(String key) {
    feedString(key);
    feed(RuleKeyHasherTypes.KEY);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putNull() {
    feed(RuleKeyHasherTypes.NULL);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putBoolean(boolean val) {
    feed(val ? RuleKeyHasherTypes.TRUE : RuleKeyHasherTypes.FALSE);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putNumber(Number val) {
    if (val instanceof Integer) { // most common, so test first
      feed((int) val);
      feed(RuleKeyHasherTypes.INTEGER);
    } else if (val instanceof Long) {
      feedLong((long) val);
      feed(RuleKeyHasherTypes.LONG);
    } else if (val instanceof Short) {
      feed((short) val);
      feed(RuleKeyHasherTypes.SHORT);
    } else if (val instanceof Byte) {
      feed((byte) val);
      feed(RuleKeyHasherTypes.BYTE);
    } else if (val instanceof Float) {
      feed(Float.floatToIntBits((float) val));
      feed(RuleKeyHasherTypes.FLOAT);
    } else if (val instanceof Double) {
      feedLong(Double.doubleToLongBits((double) val));
      feed(RuleKeyHasherTypes.DOUBLE);
    } else {
      throw new UnsupportedOperationException(("Unsupported Number type: " + val.getClass()));
    }
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putString(String val) {
    feedString(val);
    feed(RuleKeyHasherTypes.STRING);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putPattern(Pattern pattern) {
    feedString(pattern.toString());
    feed(RuleKeyHasherTypes.PATTERN);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putBytes(byte[] bytes) {
    feedBytes(bytes);
    feed(RuleKeyHasherTypes.BYTE_ARRAY);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putSha1(Sha1HashCode sha1) {
    feedString(sha1.toString());
    feed(RuleKeyHasherTypes.SHA1);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putArchiveMemberPath(ArchiveMemberPath path, HashCode hash) {
    feedString(path.toString());
    feed(RuleKeyHasherTypes.ARCHIVE_MEMBER_PATH);
    feedBytes(hash.asBytes());
    feed(RuleKeyHasherTypes.ARCHIVE_MEMBER_PATH);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putPath(Path path, HashCode hash) {
    feedString(path.toString());
    feed(RuleKeyHasherTypes.PATH);
    feedBytes(hash.asBytes());
    feed(RuleKeyHasherTypes.PATH);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putNonHashingPath(String path) {
    feedString(path);
    feed(RuleKeyHasherTypes.NON_HASHING_PATH);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putSourceRoot(SourceRoot sourceRoot) {
    feedString(sourceRoot.getName());
    feed(RuleKeyHasherTypes.SOURCE_ROOT);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putRuleKey(RuleKey ruleKey) {
    feedBytes(ruleKey.getHashCode().asBytes());
    feed(RuleKeyHasherTypes.RULE_KEY);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putBuildRuleType(BuildRuleType buildRuleType) {
    feedString(buildRuleType.toString());
    feed(RuleKeyHasherTypes.RULE_TYPE);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putBuildTarget(BuildTarget buildTarget) {
    feedString(buildTarget.getFullyQualifiedName());
    feed(RuleKeyHasherTypes.TARGET);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putBuildTargetSourcePath(
      BuildTargetSourcePath<?> targetSourcePath) {
    feedString(targetSourcePath.getTarget().getFullyQualifiedName());
    feed(RuleKeyHasherTypes.TARGET_SOURCE_PATH);
    if (targetSourcePath instanceof ExplicitBuildTargetSourcePath) {
      feedString(((ExplicitBuildTargetSourcePath) targetSourcePath).getResolvedPath().toString());
      feed(RuleKeyHasherTypes.TARGET_SOURCE_PATH);
    } else if (targetSourcePath instanceof ForwardingBuildTargetSourcePath) {
      feedString(((ForwardingBuildTargetSourcePath) targetSourcePath).getDelegate().toString());
      feed(RuleKeyHasherTypes.TARGET_SOURCE_PATH);
    }
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putContainer(Container container, int length) {
    feed(RuleKeyHasherTypes.containerSubType(container));
    feed(length);
    feed(RuleKeyHasherTypes.CONTAINER);
    return this;
  }

  @Override
  public PolynomialRuleKeyHasher putWrapper(Wrapper wrapper) {
    feed(RuleKeyHasherTypes.wrapperSubType(wrapper));
    feed(RuleKeyHasherTypes.WRAPPER);
    return this;
  }

  @Override
  public Integer hash() {
    return hash;
  }
}
