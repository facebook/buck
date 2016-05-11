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

import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import javax.annotation.Nonnull;

/**
 * A {@link RuleKeyBuilderFactory} which adds some default settings to {@link RuleKey}s.
 */
public class DefaultRuleKeyBuilderFactory extends ReflectiveRuleKeyBuilderFactory<RuleKeyBuilder> {

  protected final LoadingCache<RuleKeyAppendable, RuleKey> ruleKeyCache;
  private final FileHashCache hashCache;
  private final SourcePathResolver pathResolver;

  public DefaultRuleKeyBuilderFactory(FileHashCache hashCache, SourcePathResolver pathResolver) {
    ruleKeyCache = CacheBuilder.newBuilder().weakKeys().build(
        new CacheLoader<RuleKeyAppendable, RuleKey>() {
          @Override
          public RuleKey load(@Nonnull RuleKeyAppendable appendable) throws Exception {
            RuleKeyBuilder subKeyBuilder = newBuilder();
            appendable.appendToRuleKey(subKeyBuilder);
            return subKeyBuilder.build();
          }
        });
    this.hashCache = hashCache;
    this.pathResolver = pathResolver;
  }

  protected RuleKeyBuilderFactory getDefaultRuleKeyBuilderFactory() {
    return this;
  }

  private RuleKeyBuilder newBuilder() {
    return new RuleKeyBuilder(pathResolver, hashCache) {
      @Override
      protected RuleKeyBuilder setBuildRule(BuildRule rule) {
        return setSingleValue(getDefaultRuleKeyBuilderFactory().build(rule));
      }

      @Override
      public RuleKeyBuilder setAppendableRuleKey(String key, RuleKeyAppendable appendable) {
        RuleKey subKey = ruleKeyCache.getUnchecked(appendable);
        return setAppendableRuleKey(key, subKey);
      }
    };
  }

  @Override
  protected RuleKeyBuilder newBuilder(BuildRule rule) {
    return newBuilder();
  }

  protected void addDepsToRuleKey(RuleKeyBuilder builder, BuildRule buildRule) {
    if (buildRule instanceof AbstractBuildRule) {
      // TODO(marcinkosiba): We really need to get rid of declared/extra deps in rules. Instead
      // rules should explicitly take the needed sub-sets of deps as constructor args.
      AbstractBuildRule abstractBuildRule = (AbstractBuildRule) buildRule;
      builder
          .setReflectively("buck.extraDeps", abstractBuildRule.deprecatedGetExtraDeps())
          .setReflectively("buck.declaredDeps", abstractBuildRule.getDeclaredDeps());
    } else {
      builder.setReflectively("buck.deps", buildRule.getDeps());
    }
  }

  @Override
  public RuleKeyBuilder newInstance(BuildRule buildRule) {
    RuleKeyBuilder builder = super.newInstance(buildRule);
    addDepsToRuleKey(builder, buildRule);
    return builder;
  }

}
