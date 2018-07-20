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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.type.RuleType;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.rules.keys.SourceRoot;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * A {@link RuleKeyHasher} that forwards all the methods to the two underlying hashers.
 *
 * <p>{@link ForwardingRuleKeyHasher#hash} invokes the method of the both underlying hashers and
 * returns the hash of the first one.
 */
public abstract class ForwardingRuleKeyHasher<HASH, HASH2> implements RuleKeyHasher<HASH> {
  private final RuleKeyHasher<HASH> delegate;
  private final RuleKeyHasher<HASH2> secondHasher;

  protected ForwardingRuleKeyHasher(
      RuleKeyHasher<HASH> firstHasher, RuleKeyHasher<HASH2> secondHasher) {
    this.secondHasher = secondHasher;
    this.delegate = firstHasher;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putKey(String key) {
    secondHasher.putKey(key);
    delegate.putKey(key);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putNull() {
    secondHasher.putNull();
    delegate.putNull();
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putCharacter(char val) {
    secondHasher.putCharacter(val);
    delegate.putCharacter(val);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putBoolean(boolean val) {
    secondHasher.putBoolean(val);
    delegate.putBoolean(val);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putNumber(Number val) {
    secondHasher.putNumber(val);
    delegate.putNumber(val);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putString(String val) {
    secondHasher.putString(val);
    delegate.putString(val);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putBytes(byte[] bytes) {
    secondHasher.putBytes(bytes);
    delegate.putBytes(bytes);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putPattern(Pattern pattern) {
    secondHasher.putPattern(pattern);
    delegate.putPattern(pattern);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putSha1(Sha1HashCode sha1) {
    secondHasher.putSha1(sha1);
    delegate.putSha1(sha1);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putPath(Path path, HashCode hash) {
    secondHasher.putPath(path, hash);
    delegate.putPath(path, hash);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putArchiveMemberPath(
      ArchiveMemberPath path, HashCode hash) {
    secondHasher.putArchiveMemberPath(path, hash);
    delegate.putArchiveMemberPath(path, hash);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putNonHashingPath(String path) {
    secondHasher.putNonHashingPath(path);
    delegate.putNonHashingPath(path);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putSourceRoot(SourceRoot sourceRoot) {
    secondHasher.putSourceRoot(sourceRoot);
    delegate.putSourceRoot(sourceRoot);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putRuleKey(RuleKey ruleKey) {
    secondHasher.putRuleKey(ruleKey);
    delegate.putRuleKey(ruleKey);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putRuleType(RuleType ruleType) {
    secondHasher.putRuleType(ruleType);
    delegate.putRuleType(ruleType);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putBuildTarget(BuildTarget buildTarget) {
    secondHasher.putBuildTarget(buildTarget);
    delegate.putBuildTarget(buildTarget);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putBuildTargetSourcePath(
      BuildTargetSourcePath buildTargetSourcePath) {
    secondHasher.putBuildTargetSourcePath(buildTargetSourcePath);
    delegate.putBuildTargetSourcePath(buildTargetSourcePath);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putContainer(Container container, int length) {
    secondHasher.putContainer(container, length);
    delegate.putContainer(container, length);
    return this;
  }

  @Override
  public ForwardingRuleKeyHasher<HASH, HASH2> putWrapper(Wrapper wrapper) {
    secondHasher.putWrapper(wrapper);
    delegate.putWrapper(wrapper);
    return this;
  }

  @Override
  public HASH hash() {
    HASH hash = delegate.hash();
    onHash(hash, secondHasher.hash());
    return hash;
  }

  protected abstract void onHash(HASH firstHash, HASH2 secondHash);
}
