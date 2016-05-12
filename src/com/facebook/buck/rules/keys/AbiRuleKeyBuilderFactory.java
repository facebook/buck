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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Preconditions;

public class AbiRuleKeyBuilderFactory extends DefaultRuleKeyBuilderFactory {

  private final DefaultRuleKeyBuilderFactory defaultRuleKeyBuilderFactory;

  public AbiRuleKeyBuilderFactory(
      FileHashCache hashCache,
      SourcePathResolver pathResolver,
      DefaultRuleKeyBuilderFactory defaultRuleKeyBuilderFactory) {
    super(hashCache, pathResolver);
    this.defaultRuleKeyBuilderFactory = defaultRuleKeyBuilderFactory;
  }

  @Override
  public DefaultRuleKeyBuilderFactory getDefaultRuleKeyBuilderFactory() {
    return defaultRuleKeyBuilderFactory;
  }

  @Override
  protected void addDepsToRuleKey(RuleKeyObjectSink sink, BuildRule buildRule) {
    Preconditions.checkArgument(
        buildRule instanceof AbiRule,
        String.format(
            "Expected an AbiRule, but '%s' was a '%s'.",
            buildRule.getBuildTarget().getFullyQualifiedName(),
            buildRule.getType()));
    AbiRule abiRule = (AbiRule) buildRule;
    sink.setReflectively(
        "buck.deps",
        abiRule.getAbiKeyForDeps(defaultRuleKeyBuilderFactory));
  }

}
