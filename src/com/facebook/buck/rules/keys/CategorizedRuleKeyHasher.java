/*
 * Copyright 2017-present Facebook, Inc.
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
import com.facebook.buck.rules.RuleKeyFieldCategory;
import com.facebook.buck.rules.SourceRoot;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An implementation of {@link RuleKeyHasher} that maintains a separate hash for each field
 * category.
 */
public class CategorizedRuleKeyHasher implements RuleKeyHasher<HashCode> {
  private final Map<RuleKeyFieldCategory, PolynomialRuleKeyHasher> hashers = new HashMap<>();

  private PolynomialRuleKeyHasher current;

  public CategorizedRuleKeyHasher() {
    for (RuleKeyFieldCategory category : RuleKeyFieldCategory.values()) {
      hashers.put(category, new PolynomialRuleKeyHasher());
    }
    current = hashers.get(RuleKeyFieldCategory.UNKNOWN);
  }

  @Override
  public CategorizedRuleKeyHasher selectCategory(RuleKeyFieldCategory category) {
    current = hashers.get(category);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putKey(String key) {
    current.putKey(key);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putNull() {
    current.putNull();
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putBoolean(boolean val) {
    current.putBoolean(val);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putNumber(Number val) {
    current.putNumber(val);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putString(String val) {
    current.putString(val);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putBytes(byte[] bytes) {
    current.putBytes(bytes);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putPattern(Pattern pattern) {
    current.putPattern(pattern);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putSha1(Sha1HashCode sha1) {
    current.putSha1(sha1);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putPath(Path path, HashCode hash) {
    current.putPath(path, hash);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putArchiveMemberPath(ArchiveMemberPath path, HashCode hash) {
    current.putArchiveMemberPath(path, hash);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putNonHashingPath(String path) {
    current.putNonHashingPath(path);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putSourceRoot(SourceRoot sourceRoot) {
    current.putSourceRoot(sourceRoot);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putRuleKey(RuleKey ruleKey) {
    current.putRuleKey(ruleKey);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putBuildRuleType(BuildRuleType buildRuleType) {
    current.putBuildRuleType(buildRuleType);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putBuildTarget(BuildTarget buildTarget) {
    current.putBuildTarget(buildTarget);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putBuildTargetSourcePath(
      BuildTargetSourcePath<?> buildTargetSourcePath) {
    current.putBuildTargetSourcePath(buildTargetSourcePath);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putContainer(Container container, int length) {
    current.putContainer(container, length);
    return this;
  }

  @Override
  public CategorizedRuleKeyHasher putWrapper(Wrapper wrapper) {
    current.putWrapper(wrapper);
    return this;
  }

  @Override
  public HashCode hash() {
    byte[] bytes = new byte[RuleKeyFieldCategory.values().length * 4];
    int offset = 0;
    for (RuleKeyFieldCategory category : RuleKeyFieldCategory.values()) {
      writeBytes(bytes, offset, hashers.get(category).hash());
      offset += 4;
    }
    return HashCode.fromBytes(bytes);
  }

  private static void writeBytes(byte[] bytes, int offset, int val) {
    bytes[offset] = (byte) (val);
    bytes[offset + 1] = (byte) (val >>> 8);
    bytes[offset + 2] = (byte) (val >>> 16);
    bytes[offset + 3] = (byte) (val >>> 24);
  }
}
