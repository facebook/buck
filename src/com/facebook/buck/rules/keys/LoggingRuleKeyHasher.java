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
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourceRoot;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.annotations.VisibleForTesting;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * A delegating {@link RuleKeyHasher} that also does verbose rule key logging.
 */
public class LoggingRuleKeyHasher<HASH> implements RuleKeyHasher<HASH> {
  private static final Logger logger = Logger.get(RuleKeyBuilder.class);
  private final RuleKeyHasher<String> stringHasher;
  private final RuleKeyHasher<HASH> delegate;

  public static <HASH> RuleKeyHasher<HASH> of(RuleKeyHasher<HASH> delegate) {
    if (!logger.isVerboseEnabled()) {
      return delegate;
    }
    return new LoggingRuleKeyHasher<>(delegate, new StringRuleKeyHasher());
  }

  @VisibleForTesting
  LoggingRuleKeyHasher(RuleKeyHasher<HASH> delegate, RuleKeyHasher<String> stringHasher) {
    this.stringHasher = stringHasher;
    this.delegate = delegate;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putKey(String key) {
    stringHasher.putKey(key);
    delegate.putKey(key);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putNull() {
    stringHasher.putNull();
    delegate.putNull();
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putBoolean(boolean val) {
    stringHasher.putBoolean(val);
    delegate.putBoolean(val);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putNumber(Number val) {
    stringHasher.putNumber(val);
    delegate.putNumber(val);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putString(CharSequence val) {
    stringHasher.putString(val);
    delegate.putString(val);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putBytes(byte[] bytes) {
    stringHasher.putBytes(bytes);
    delegate.putBytes(bytes);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putPattern(Pattern pattern) {
    stringHasher.putPattern(pattern);
    delegate.putPattern(pattern);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putSha1(Sha1HashCode sha1) {
    stringHasher.putSha1(sha1);
    delegate.putSha1(sha1);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putPath(Path path, String hash) {
    stringHasher.putPath(path, hash);
    delegate.putPath(path, hash);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putArchiveMemberPath(ArchiveMemberPath path, String hash) {
    stringHasher.putArchiveMemberPath(path, hash);
    delegate.putArchiveMemberPath(path, hash);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putNonHashingPath(String path) {
    stringHasher.putNonHashingPath(path);
    delegate.putNonHashingPath(path);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putSourceRoot(SourceRoot sourceRoot) {
    stringHasher.putSourceRoot(sourceRoot);
    delegate.putSourceRoot(sourceRoot);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putRuleKey(RuleKey ruleKey) {
    stringHasher.putRuleKey(ruleKey);
    delegate.putRuleKey(ruleKey);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putBuildRuleType(BuildRuleType buildRuleType) {
    stringHasher.putBuildRuleType(buildRuleType);
    delegate.putBuildRuleType(buildRuleType);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putBuildTarget(BuildTarget buildTarget) {
    stringHasher.putBuildTarget(buildTarget);
    delegate.putBuildTarget(buildTarget);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putBuildTargetSourcePath(
      BuildTargetSourcePath buildTargetSourcePath) {
    stringHasher.putBuildTargetSourcePath(buildTargetSourcePath);
    delegate.putBuildTargetSourcePath(buildTargetSourcePath);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putContainer(Container container, int length) {
    stringHasher.putContainer(container, length);
    delegate.putContainer(container, length);
    return this;
  }

  @Override
  public LoggingRuleKeyHasher<HASH> putWrapper(Wrapper wrapper) {
    stringHasher.putWrapper(wrapper);
    delegate.putWrapper(wrapper);
    return this;
  }

  @Override
  public HASH hash() {
    HASH hash = delegate.hash();
    logger.verbose("RuleKey %s=%s", hash, stringHasher.hash());
    return hash;
  }
}
