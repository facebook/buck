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

package com.facebook.buck.rules.keys;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.hash.HashCode;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nonnull;

/**
 * A factory for generating {@link RuleKey}s that only take into the account the path of a file
 * and not the contents(hash) of the file.
 */
public class ContentAgnosticRuleKeyBuilderFactory
    extends ReflectiveRuleKeyBuilderFactory<RuleKeyBuilder> {

  private final FileHashCache fileHashCache;
  private final SourcePathResolver pathResolver;
  private final LoadingCache<RuleKeyAppendable, RuleKey> ruleKeyCache;

  public ContentAgnosticRuleKeyBuilderFactory(
      SourcePathResolver pathResolver
  ) {
    // Build the cache around the sub-rule-keys and their dep lists.
    ruleKeyCache = CacheBuilder.newBuilder().weakKeys().build(
        new CacheLoader<RuleKeyAppendable, RuleKey>() {
          @Override
          public RuleKey load(@Nonnull RuleKeyAppendable appendable) throws Exception {
            RuleKeyBuilder subKeyBuilder = newBuilder();
            appendable.appendToRuleKey(subKeyBuilder);
            return subKeyBuilder.build();
          }
        });

    this.pathResolver = pathResolver;
    this.fileHashCache = new FileHashCache() {

      @Override
      public HashCode get(Path path) throws IOException {
        return HashCode.fromLong(0);
      }

      @Override
      public boolean willGet(Path path) {
        return true;
      }

      @Override
      public void invalidate(Path path) {}

      @Override
      public void invalidateAll() {}
    };
  }

  private RuleKeyBuilder newBuilder() {
    return new RuleKeyBuilder(pathResolver, fileHashCache, this) {
      @Override
      protected RuleKey getAppendableRuleKey(
          SourcePathResolver resolver,
          FileHashCache hashCache,
          RuleKeyAppendable appendable) {
        return ruleKeyCache.getUnchecked(appendable);
      }
    };
  }

  @Override
  protected RuleKeyBuilder newBuilder(final BuildRule rule) {
    return newBuilder();
  }
}
