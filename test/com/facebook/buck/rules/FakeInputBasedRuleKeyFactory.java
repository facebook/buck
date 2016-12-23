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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.keys.SizeLimiter;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import javax.annotation.Nullable;

public class FakeInputBasedRuleKeyFactory
    implements RuleKeyFactory<RuleKey> {

  private final ImmutableMap<BuildTarget, RuleKey> ruleKeys;
  private final ImmutableSet<BuildTarget> oversized;
  private final FileHashCache fileHashCache;

  public FakeInputBasedRuleKeyFactory(
      ImmutableMap<BuildTarget, RuleKey> ruleKeys,
      ImmutableSet<BuildTarget> oversized,
      FileHashCache fileHashCache) {
    this.ruleKeys = ruleKeys;
    this.oversized = oversized;
    this.fileHashCache = fileHashCache;
  }

  public FakeInputBasedRuleKeyFactory(
      ImmutableMap<BuildTarget, RuleKey> ruleKeys,
      FileHashCache fileHashCache) {
    this(ruleKeys, ImmutableSet.of(), fileHashCache);
  }

  public FakeInputBasedRuleKeyFactory(
      ImmutableMap<BuildTarget, RuleKey> ruleKeys) {
    this(ruleKeys, new NullFileHashCache());
  }

  private RuleKeyBuilder<RuleKey> newInstance(final BuildRule buildRule) {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    return new RuleKeyBuilder<RuleKey>(ruleFinder, resolver, fileHashCache) {

      @Override
      protected RuleKeyBuilder<RuleKey> setBuildRule(BuildRule rule) {
        return this;
      }

      @Override
      public RuleKeyBuilder<RuleKey> setReflectively(String key, @Nullable Object val) {
        return this;
      }

      @Override
      public RuleKeyBuilder<RuleKey> setAppendableRuleKey(
          String key,
          RuleKeyAppendable appendable) {
        return this;
      }

      @Override
      public RuleKey build() {
        if (oversized.contains(buildRule.getBuildTarget())) {
          throw new SizeLimiter.SizeLimitException();
        }
        return ruleKeys.get(buildRule.getBuildTarget());
      }

    };
  }

  @Override
  public RuleKey build(BuildRule buildRule) {
    return newInstance(buildRule).build();
  }

}
