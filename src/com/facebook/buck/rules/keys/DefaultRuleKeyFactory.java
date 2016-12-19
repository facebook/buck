/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.RuleKeyFactory;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import javax.annotation.Nonnull;

/**
 * A {@link RuleKeyFactory} which adds some default settings to {@link RuleKey}s.
 */
public class DefaultRuleKeyFactory
    extends ReflectiveRuleKeyFactory<RuleKeyBuilder<RuleKey>, RuleKey> {

  protected final LoadingCache<RuleKeyAppendable, RuleKey> ruleKeyCache;
  private final FileHashLoader hashLoader;
  private final SourcePathResolver pathResolver;

  public DefaultRuleKeyFactory(
      int seed,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver) {
    super(seed);
    this.ruleKeyCache = CacheBuilder.newBuilder().weakKeys().build(
        new CacheLoader<RuleKeyAppendable, RuleKey>() {
          @Override
          public RuleKey load(@Nonnull RuleKeyAppendable appendable) throws Exception {
            RuleKeyBuilder<RuleKey> subKeyBuilder = newBuilder();
            appendable.appendToRuleKey(subKeyBuilder);
            return subKeyBuilder.build();
          }
        });
    this.hashLoader = hashLoader;
    this.pathResolver = pathResolver;
  }

  private RuleKeyBuilder<RuleKey> newBuilder() {
    return new RuleKeyBuilder<RuleKey>(pathResolver, hashLoader) {
      @Override
      protected RuleKeyBuilder<RuleKey> setBuildRule(BuildRule rule) {
        return setSingleValue(DefaultRuleKeyFactory.this.build(rule));
      }

      @Override
      public RuleKeyBuilder<RuleKey> setAppendableRuleKey(
          String key,
          RuleKeyAppendable appendable) {
        RuleKey subKey = ruleKeyCache.getUnchecked(appendable);
        return setAppendableRuleKey(key, subKey);
      }

      @Override
      public RuleKey build() {
        return buildRuleKey();
      }
    };
  }

  @Override
  protected RuleKeyBuilder<RuleKey> newBuilder(BuildRule rule) {
    RuleKeyBuilder<RuleKey> builder = newBuilder();
    addDepsToRuleKey(builder, rule);
    return builder;
  }

  private void addDepsToRuleKey(RuleKeyObjectSink sink, BuildRule buildRule) {
    if (buildRule instanceof AbstractBuildRule) {
      // TODO(marcinkosiba): We really need to get rid of declared/extra deps in rules. Instead
      // rules should explicitly take the needed sub-sets of deps as constructor args.
      AbstractBuildRule abstractBuildRule = (AbstractBuildRule) buildRule;
      sink
          .setReflectively("buck.extraDeps", abstractBuildRule.deprecatedGetExtraDeps())
          .setReflectively("buck.declaredDeps", abstractBuildRule.getDeclaredDeps());
    } else {
      sink.setReflectively("buck.deps", buildRule.getDeps());
    }
  }
}
