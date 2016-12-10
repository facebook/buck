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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Preconditions;

public class AbiRuleKeyFactory extends DefaultRuleKeyFactory {

  private final DefaultRuleKeyFactory defaultRuleKeyFactory;

  public AbiRuleKeyFactory(
      int seed,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver,
      DefaultRuleKeyFactory defaultRuleKeyFactory) {
    super(seed, hashLoader, pathResolver);
    this.defaultRuleKeyFactory = defaultRuleKeyFactory;
  }

  @Override
  public DefaultRuleKeyFactory getDefaultRuleKeyFactory() {
    return defaultRuleKeyFactory;
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
        abiRule.getAbiKeyForDeps(defaultRuleKeyFactory));
  }

}
