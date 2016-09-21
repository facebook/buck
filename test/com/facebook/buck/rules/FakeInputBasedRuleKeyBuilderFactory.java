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
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import javax.annotation.Nullable;

public class FakeInputBasedRuleKeyBuilderFactory
    implements RuleKeyBuilderFactory<Optional<RuleKey>> {

  private final ImmutableMap<BuildTarget, Optional<RuleKey>> ruleKeys;
  private final FileHashCache fileHashCache;

  public FakeInputBasedRuleKeyBuilderFactory(
      ImmutableMap<BuildTarget, Optional<RuleKey>> ruleKeys,
      FileHashCache fileHashCache) {
    this.ruleKeys = ruleKeys;
    this.fileHashCache = fileHashCache;
  }

  public FakeInputBasedRuleKeyBuilderFactory(
      ImmutableMap<BuildTarget, Optional<RuleKey>> ruleKeys) {
    this(ruleKeys, new NullFileHashCache());
  }

  private RuleKeyBuilder<Optional<RuleKey>> newInstance(final BuildRule buildRule) {
    SourcePathResolver resolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
     );
    return new RuleKeyBuilder<Optional<RuleKey>>(resolver, fileHashCache) {

      @Override
      protected RuleKeyBuilder<Optional<RuleKey>> setBuildRule(BuildRule rule) {
        return this;
      }

      @Override
      public RuleKeyBuilder<Optional<RuleKey>> setReflectively(String key, @Nullable Object val) {
        return this;
      }

      @Override
      public RuleKeyBuilder<Optional<RuleKey>> setAppendableRuleKey(
          String key,
          RuleKeyAppendable appendable) {
        return this;
      }

      @Override
      public Optional<RuleKey> build() {
        return ruleKeys.get(buildRule.getBuildTarget());
      }

    };
  }

  @Override
  public Optional<RuleKey> build(BuildRule buildRule) {
    return newInstance(buildRule).build();
  }

}
