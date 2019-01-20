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

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.util.hashing.FileHashLoader;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A factory for generating {@link RuleKey}s that only take into the account the path of a file and
 * not the contents(hash) of the file.
 */
public class ContentAgnosticRuleKeyFactory implements RuleKeyFactory<RuleKey> {

  private final RuleKeyFieldLoader ruleKeyFieldLoader;
  private final SourcePathResolver pathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final Optional<ThriftRuleKeyLogger> ruleKeyLogger;

  private final FileHashLoader fileHashLoader =
      new FileHashLoader() {

        @Override
        public HashCode get(Path path) {
          return HashCode.fromLong(0);
        }

        @Override
        public long getSize(Path path) {
          return 0;
        }

        @Override
        public HashCode get(ArchiveMemberPath archiveMemberPath) {
          throw new AssertionError();
        }
      };

  private final SingleBuildRuleKeyCache<RuleKey> ruleKeyCache = new SingleBuildRuleKeyCache<>();

  public ContentAgnosticRuleKeyFactory(
      RuleKeyFieldLoader ruleKeyFieldLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      Optional<ThriftRuleKeyLogger> ruleKeyLogger) {
    this.ruleKeyFieldLoader = ruleKeyFieldLoader;
    this.pathResolver = pathResolver;
    this.ruleFinder = ruleFinder;
    this.ruleKeyLogger = ruleKeyLogger;
  }

  private RuleKey calculateBuildRuleKey(BuildRule buildRule) {
    Builder<HashCode> builder = new Builder<>(RuleKeyBuilder.createDefaultHasher(ruleKeyLogger));
    ruleKeyFieldLoader.setFields(builder, buildRule, RuleKeyType.CONTENT_AGNOSTIC);
    return builder.build(RuleKey::new);
  }

  private RuleKey calculateAppendableKey(AddsToRuleKey appendable) {
    Builder<HashCode> subKeyBuilder =
        new Builder<>(RuleKeyBuilder.createDefaultHasher(ruleKeyLogger));
    AlterRuleKeys.amendKey(subKeyBuilder, appendable);
    return subKeyBuilder.build(RuleKey::new);
  }

  @Override
  public RuleKey build(BuildRule buildRule) {
    return ruleKeyCache.get(buildRule, this::calculateBuildRuleKey);
  }

  private RuleKey buildAppendableKey(AddsToRuleKey appendable) {
    return ruleKeyCache.get(appendable, this::calculateAppendableKey);
  }

  public class Builder<RULE_KEY> extends RuleKeyBuilder<RULE_KEY> {

    public Builder(RuleKeyHasher<RULE_KEY> hasher) {
      super(ruleFinder, pathResolver, fileHashLoader, hasher);
    }

    @Override
    protected RuleKeyBuilder<RULE_KEY> setBuildRule(BuildRule rule) {
      return setBuildRuleKey(ContentAgnosticRuleKeyFactory.this.build(rule));
    }

    @Override
    protected RuleKeyBuilder<RULE_KEY> setAddsToRuleKey(AddsToRuleKey appendable) {
      return setAddsToRuleKey(ContentAgnosticRuleKeyFactory.this.buildAppendableKey(appendable));
    }

    @Override
    protected RuleKeyBuilder<RULE_KEY> setSourcePath(SourcePath sourcePath) throws IOException {
      if (sourcePath instanceof BuildTargetSourcePath) {
        return setSourcePathAsRule((BuildTargetSourcePath) sourcePath);
      } else {
        return setSourcePathDirectly(sourcePath);
      }
    }

    @Override
    protected RuleKeyBuilder<RULE_KEY> setNonHashingSourcePath(SourcePath sourcePath) {
      return setNonHashingSourcePathDirectly(sourcePath);
    }
  }
}
