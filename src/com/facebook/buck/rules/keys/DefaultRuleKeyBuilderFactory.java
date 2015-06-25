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
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.FileHashCache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import javax.annotation.Nonnull;

public class DefaultRuleKeyBuilderFactory extends AbstractRuleKeyBuilderFactory {

  private final LoadingCache<RuleKeyAppendable, RuleKey> cache;

  public DefaultRuleKeyBuilderFactory(
      final FileHashCache hashCache,
      final SourcePathResolver pathResolver) {
    super(hashCache, pathResolver);
    cache = CacheBuilder.newBuilder().weakKeys().build(
        new CacheLoader<RuleKeyAppendable, RuleKey>() {
          @Override
          public RuleKey load(@Nonnull RuleKeyAppendable appendable) throws Exception {
            RuleKey.Builder subKeyBuilder = newBuilder(pathResolver, hashCache);
            appendable.appendToRuleKey(subKeyBuilder);
            return subKeyBuilder.build();
          }
        });
  }

  private RuleKey.Builder newBuilder(
      SourcePathResolver pathResolver,
      FileHashCache hashCache) {
    return new RuleKey.Builder(
        pathResolver,
        hashCache) {
      @Override
      protected RuleKey getAppendableRuleKey(
          SourcePathResolver resolver,
          FileHashCache hashCache,
          RuleKeyAppendable appendable) {
        return cache.getUnchecked(appendable);
      }
    };
  }

  @Override
  protected RuleKey.Builder newBuilder(
      SourcePathResolver pathResolver,
      FileHashCache hashCache,
      BuildRule rule) {
    return newBuilder(pathResolver, hashCache);
  }

}
