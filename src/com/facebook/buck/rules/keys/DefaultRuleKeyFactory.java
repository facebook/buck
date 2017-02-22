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
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import java.io.IOException;

/**
 * A {@link RuleKeyFactory} which adds some default settings to {@link RuleKey}s.
 */
public class DefaultRuleKeyFactory implements RuleKeyFactory<RuleKey> {

  private final RuleKeyFieldLoader ruleKeyFieldLoader;
  private final FileHashLoader hashLoader;
  private final SourcePathResolver pathResolver;
  private final SourcePathRuleFinder ruleFinder;
  private final RuleKeyCache<RuleKey> ruleKeyCache;

  public DefaultRuleKeyFactory(
      RuleKeyFieldLoader ruleKeyFieldLoader,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      RuleKeyCache<RuleKey> ruleKeyCache) {
    this.ruleKeyFieldLoader = ruleKeyFieldLoader;
    this.hashLoader = hashLoader;
    this.pathResolver = pathResolver;
    this.ruleFinder = ruleFinder;
    this.ruleKeyCache = ruleKeyCache;
  }

  public DefaultRuleKeyFactory(
      RuleKeyFieldLoader ruleKeyFieldLoader,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder) {
    this(
        ruleKeyFieldLoader,
        hashLoader,
        pathResolver,
        ruleFinder,
        new DefaultRuleKeyCache<>());
  }

  public DefaultRuleKeyFactory(
      int seed,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder) {
    this(new RuleKeyFieldLoader(seed), hashLoader, pathResolver, ruleFinder);
  }

  private RuleKeyBuilder<RuleKeyResult<RuleKey>> newPopulatedBuilder(BuildRule buildRule) {
    RuleKeyBuilder<RuleKeyResult<RuleKey>> builder = newBuilder();
    ruleKeyFieldLoader.setFields(buildRule, builder);
    addDepsToRuleKey(buildRule, builder);
    return builder;
  }

  private RuleKeyResult<RuleKey> calculateBuildRuleKey(BuildRule buildRule) {
    return newPopulatedBuilder(buildRule).build();
  }

  @Override
  public RuleKey build(BuildRule buildRule) {
    return ruleKeyCache.get(buildRule, this::calculateBuildRuleKey);
  }

  @VisibleForTesting
  public RuleKeyBuilder<RuleKeyResult<RuleKey>> newBuilderForTesting(BuildRule buildRule) {
    return newPopulatedBuilder(buildRule);
  }

  private void addDepsToRuleKey(BuildRule buildRule, RuleKeyObjectSink sink) {
    if (buildRule instanceof AbstractBuildRule) {
      // TODO(marcinkosiba): We really need to get rid of declared/extra deps in rules. Instead
      // rules should explicitly take the needed sub-sets of deps as constructor args.
      AbstractBuildRule abstractBuildRule = (AbstractBuildRule) buildRule;
      sink.setReflectively("buck.extraDeps", abstractBuildRule.deprecatedGetExtraDeps());
      sink.setReflectively("buck.declaredDeps", abstractBuildRule.getDeclaredDeps());
    } else {
      sink.setReflectively("buck.deps", buildRule.getDeps());
    }
  }

  private RuleKeyBuilder<RuleKeyResult<RuleKey>> newBuilder() {
    return new RuleKeyBuilder<RuleKeyResult<RuleKey>>(ruleFinder, pathResolver, hashLoader) {

      private final ImmutableList.Builder<Object> deps = ImmutableList.builder();
      private final ImmutableList.Builder<RuleKeyInput> inputs = ImmutableList.builder();

      @Override
      protected RuleKeyBuilder<RuleKeyResult<RuleKey>> setBuildRule(BuildRule rule) {

        // Record the `BuildRule` as an immediate dep.
        deps.add(rule);

        return setBuildRuleKey(DefaultRuleKeyFactory.this.build(rule));
      }

      private RuleKeyResult<RuleKey> calculateRuleKeyAppendableKey(RuleKeyAppendable appendable) {
        RuleKeyBuilder<RuleKeyResult<RuleKey>> subKeyBuilder = newBuilder();
        appendable.appendToRuleKey(subKeyBuilder);
        return subKeyBuilder.build();
      }

      @Override
      protected RuleKeyBuilder<RuleKeyResult<RuleKey>> setAppendableRuleKey(
          RuleKeyAppendable appendable) {

        // Record the `RuleKeyAppendable` as an immediate dep.
        deps.add(appendable);

        // Calculate the rule key for the rule key appendable.
        RuleKey ruleKey = ruleKeyCache.get(appendable, this::calculateRuleKeyAppendableKey);

        return setAppendableRuleKey(ruleKey);
      }

      @Override
      protected RuleKeyBuilder<RuleKeyResult<RuleKey>> setSourcePath(SourcePath sourcePath)
          throws IOException {
        if (sourcePath instanceof BuildTargetSourcePath) {
          return setSourcePathAsRule((BuildTargetSourcePath) sourcePath);
        } else {

          // Add `PathSourcePath`s to our tracked inputs.
          pathResolver.getPathSourcePath(sourcePath).ifPresent(
              path -> inputs.add(RuleKeyInput.of(path.getFilesystem(), path.getRelativePath())));

          return setSourcePathDirectly(sourcePath);
        }
      }

      @Override
      public RuleKeyResult<RuleKey> build() {
        return new RuleKeyResult<>(buildRuleKey(), deps.build(), inputs.build());
      }

    };
  }

}
