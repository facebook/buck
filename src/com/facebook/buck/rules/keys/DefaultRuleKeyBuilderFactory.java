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

import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Function;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;

import java.util.concurrent.ExecutionException;

import javax.annotation.Nonnull;

/**
 * A {@link RuleKeyBuilderFactory} which adds some default settings to {@link RuleKey}s.
 */
public class DefaultRuleKeyBuilderFactory implements RuleKeyBuilderFactory {

  protected static final Function<Pair<RuleKeyBuilder, BuildRule>, RuleKeyBuilder>
      DEFAULT_ADD_DEPS_TO_RULE_KEY =
      new Function<Pair<RuleKeyBuilder, BuildRule>, RuleKeyBuilder>() {
        @Override
        public RuleKeyBuilder apply(Pair<RuleKeyBuilder, BuildRule> input) {
          return input.getFirst().setReflectively("buck.deps", input.getSecond().getDeps());
        }
      };
  protected static final Function<Pair<RuleKeyBuilder, BuildRule>, RuleKeyBuilder>
      NOOP_ADD_DEPS_TO_RULE_KEY =
      new Function<Pair<RuleKeyBuilder, BuildRule>, RuleKeyBuilder>() {
        @Override
        public RuleKeyBuilder apply(Pair<RuleKeyBuilder, BuildRule> input) {
          return input.getFirst();
        }
      };

  protected final LoadingCache<RuleKeyAppendable, RuleKey> ruleKeyCache;
  private final FileHashCache hashCache;
  private final SourcePathResolver pathResolver;
  private final Function<Pair<RuleKeyBuilder, BuildRule>, RuleKeyBuilder> addDepsToRuleKey;
  private final LoadingCache<Class<? extends BuildRule>, ImmutableCollection<AlterRuleKey>>
      knownFields;

  protected DefaultRuleKeyBuilderFactory(
      final FileHashCache hashCache,
      final SourcePathResolver pathResolver,
      Function<Pair<RuleKeyBuilder, BuildRule>, RuleKeyBuilder>addDepsToRuleKey) {
    ruleKeyCache = CacheBuilder.newBuilder().weakKeys().build(
        new CacheLoader<RuleKeyAppendable, RuleKey>() {
          @Override
          public RuleKey load(@Nonnull RuleKeyAppendable appendable) throws Exception {
            RuleKeyBuilder subKeyBuilder = newBuilder(pathResolver, hashCache);
            appendable.appendToRuleKey(subKeyBuilder);
            return subKeyBuilder.build();
          }
        });
    this.hashCache = hashCache;
    this.pathResolver = pathResolver;
    this.addDepsToRuleKey = addDepsToRuleKey;
    knownFields = CacheBuilder.newBuilder().build(new ReflectiveAlterKeyLoader());
  }

  public DefaultRuleKeyBuilderFactory(
      final FileHashCache hashCache,
      final SourcePathResolver pathResolver) {
    this(
        hashCache,
        pathResolver,
        DEFAULT_ADD_DEPS_TO_RULE_KEY);
  }

  protected RuleKeyBuilder newBuilder(
      SourcePathResolver pathResolver,
      FileHashCache hashCache) {
    return new RuleKeyBuilder(
        pathResolver,
        hashCache) {
      @Override
      protected RuleKey getAppendableRuleKey(
          SourcePathResolver resolver,
          FileHashCache hashCache,
          RuleKeyAppendable appendable) {
        return ruleKeyCache.getUnchecked(appendable);
      }
    };
  }

  protected RuleKeyBuilder newBuilder(
      SourcePathResolver pathResolver,
      FileHashCache hashCache,
      BuildRule rule) {
    return addDepsToRuleKey.apply(
        new Pair<>(newBuilder(pathResolver, hashCache), rule));
  }

  /**
   * Initialize a new {@link RuleKeyBuilder}.
   */
  @Override
  public final RuleKeyBuilder newInstance(BuildRule buildRule) {
    RuleKeyBuilder builder = newBuilder(pathResolver, hashCache, buildRule);
    builder.setReflectively("name", buildRule.getBuildTarget().getFullyQualifiedName());
    // Keyed as "buck.type" rather than "type" in case a build rule has its own "type" argument.
    builder.setReflectively("buck.type", buildRule.getType());
    builder.setReflectively("buckVersionUid", BuckVersion.getVersion());

    if (buildRule instanceof RuleKeyAppendable) {
      // We call `setAppendableRuleKey` explicitly, since using `setReflectively` will try to add
      // the rule key of the `BuildRule`, which is what we're trying to calculate now.
      //
      // "." is not a valid first character for a field name, and so will never be seen in the
      // reflective rule key setting.
      builder.setAppendableRuleKey(".buck", (RuleKeyAppendable) buildRule);
    }

    try {
      for (AlterRuleKey alterRuleKey : knownFields.get(buildRule.getClass())) {
        alterRuleKey.amendKey(builder, buildRule);
      }
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }

    return builder;
  }

}
