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

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourceRoot;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** A delegating {@link RuleKeyHasher} that counts the number of values put in it. */
public class CountingRuleKeyHasher<HASH> implements RuleKeyHasher<HASH> {
  private final RuleKeyHasher<HASH> delegate;

  private long count = 0;

  public CountingRuleKeyHasher(RuleKeyHasher<HASH> delegate) {
    this.delegate = delegate;
  }

  public long getCount() {
    return count;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putKey(String key) {
    count++;
    delegate.putKey(key);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putNull() {
    count++;
    delegate.putNull();
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putCharacter(char val) {
    count++;
    delegate.putCharacter(val);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putBoolean(boolean val) {
    count++;
    delegate.putBoolean(val);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putNumber(Number val) {
    count++;
    delegate.putNumber(val);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putString(String val) {
    count++;
    delegate.putString(val);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putBytes(byte[] bytes) {
    count++;
    delegate.putBytes(bytes);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putPattern(Pattern pattern) {
    count++;
    delegate.putPattern(pattern);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putSha1(Sha1HashCode sha1) {
    count++;
    delegate.putSha1(sha1);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putPath(Path path, HashCode hash) {
    count++;
    delegate.putPath(path, hash);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putArchiveMemberPath(ArchiveMemberPath path, HashCode hash) {
    count++;
    delegate.putArchiveMemberPath(path, hash);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putNonHashingPath(String path) {
    count++;
    delegate.putNonHashingPath(path);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putSourceRoot(SourceRoot sourceRoot) {
    count++;
    delegate.putSourceRoot(sourceRoot);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putRuleKey(RuleKey ruleKey) {
    count++;
    delegate.putRuleKey(ruleKey);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putBuildRuleType(BuildRuleType buildRuleType) {
    count++;
    delegate.putBuildRuleType(buildRuleType);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putBuildTarget(BuildTarget buildTarget) {
    count++;
    delegate.putBuildTarget(buildTarget);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putBuildTargetSourcePath(
      BuildTargetSourcePath buildTargetSourcePath) {
    count++;
    delegate.putBuildTargetSourcePath(buildTargetSourcePath);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putContainer(Container container, int length) {
    count++;
    delegate.putContainer(container, length);
    return this;
  }

  @Override
  public CountingRuleKeyHasher<HASH> putWrapper(Wrapper wrapper) {
    count++;
    delegate.putWrapper(wrapper);
    return this;
  }

  @Override
  public HASH hash() {
    return delegate.hash();
  }
}
