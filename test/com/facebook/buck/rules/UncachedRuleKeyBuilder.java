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

import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.base.Supplier;

public class UncachedRuleKeyBuilder extends RuleKeyBuilder {

  private final Supplier<UncachedRuleKeyBuilder> subKeySupplier;

  public UncachedRuleKeyBuilder(
      SourcePathResolver resolver,
      FileHashCache hashCache,
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      RuleKeyLogger ruleKeyLogger) {
    super(resolver, hashCache, ruleKeyBuilderFactory, ruleKeyLogger);
    subKeySupplier = createSubKeySupplier(resolver, hashCache, ruleKeyBuilderFactory);
  }

  public UncachedRuleKeyBuilder(
      SourcePathResolver resolver,
      FileHashCache hashCache,
      RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    super(resolver, hashCache, ruleKeyBuilderFactory);
    subKeySupplier = createSubKeySupplier(resolver, hashCache, ruleKeyBuilderFactory);
  }

  private static Supplier<UncachedRuleKeyBuilder> createSubKeySupplier(
      final SourcePathResolver resolver,
      final FileHashCache hashCache,
      final RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    return new Supplier<UncachedRuleKeyBuilder>() {
      @Override
      public UncachedRuleKeyBuilder get() {
        return new UncachedRuleKeyBuilder(
            resolver,
            hashCache,
            ruleKeyBuilderFactory);
      }
    };
  }

  @Override
  public RuleKeyBuilder setAppendableRuleKey(String key, RuleKeyAppendable appendable) {
    RuleKeyBuilder subKeyBuilder = subKeySupplier.get();
    appendable.appendToRuleKey(subKeyBuilder);
    RuleKey subKey = subKeyBuilder.build();
    return setAppendableRuleKey(key, subKey);
  }

}
