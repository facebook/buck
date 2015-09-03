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

import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;

public class AbiRuleKeyBuilderFactory extends DefaultRuleKeyBuilderFactory {

  public AbiRuleKeyBuilderFactory(
      FileHashCache hashCache,
      SourcePathResolver pathResolver) {
    super(
        hashCache,
        pathResolver,
        new Function<Pair<RuleKeyBuilder, BuildRule>, RuleKeyBuilder>() {
          @Override
          public RuleKeyBuilder apply(Pair<RuleKeyBuilder, BuildRule> input) {
            Preconditions.checkArgument(
                input.getSecond() instanceof AbiRule,
                String.format(
                    "Expected an AbiRule, but '%s' was a '%s'.",
                    input.getSecond().getBuildTarget().getFullyQualifiedName(),
                    input.getSecond().getType()));
            AbiRule abiRule = (AbiRule) input.getSecond();
            return input.getFirst().setReflectively("buck.deps", abiRule.getAbiKeyForDeps());
          }
        });
  }

  public class Builder extends RuleKeyBuilder {

    private Builder(
        SourcePathResolver pathResolver,
        FileHashCache hashCache) {
      super(pathResolver, hashCache);
    }


  }
}
