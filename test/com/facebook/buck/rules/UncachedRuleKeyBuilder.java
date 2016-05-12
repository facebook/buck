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

public class UncachedRuleKeyBuilder extends RuleKeyBuilder<RuleKey> {

  private final RuleKeyBuilderFactory<RuleKey> ruleKeyBuilderFactory;
  private final Supplier<UncachedRuleKeyBuilder> subKeySupplier;

  public UncachedRuleKeyBuilder(
      SourcePathResolver resolver,
      FileHashCache hashCache,
      RuleKeyBuilderFactory<RuleKey> ruleKeyBuilderFactory,
      RuleKeyLogger ruleKeyLogger) {
    super(resolver, hashCache, ruleKeyLogger);
    this.ruleKeyBuilderFactory = ruleKeyBuilderFactory;
    this.subKeySupplier = createSubKeySupplier(resolver, hashCache, ruleKeyBuilderFactory);
  }

  public UncachedRuleKeyBuilder(
      SourcePathResolver resolver,
      FileHashCache hashCache,
      RuleKeyBuilderFactory<RuleKey> ruleKeyBuilderFactory) {
    super(resolver, hashCache);
    this.ruleKeyBuilderFactory = ruleKeyBuilderFactory;
    this.subKeySupplier = createSubKeySupplier(resolver, hashCache, ruleKeyBuilderFactory);
  }

  private static Supplier<UncachedRuleKeyBuilder> createSubKeySupplier(
      final SourcePathResolver resolver,
      final FileHashCache hashCache,
      final RuleKeyBuilderFactory<RuleKey> ruleKeyBuilderFactory) {
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
  protected UncachedRuleKeyBuilder setBuildRule(BuildRule rule) {
    setSingleValue(ruleKeyBuilderFactory.build(rule));
    return this;
  }

  @Override
  public UncachedRuleKeyBuilder setAppendableRuleKey(String key, RuleKeyAppendable appendable) {
    RuleKeyBuilder<RuleKey> subKeyBuilder = subKeySupplier.get();
    appendable.appendToRuleKey(subKeyBuilder);
    RuleKey subKey = subKeyBuilder.build();
    setAppendableRuleKey(key, subKey);
    return this;
  }

  @Override
  public RuleKey build() {
    return buildRuleKey();
  }

}
