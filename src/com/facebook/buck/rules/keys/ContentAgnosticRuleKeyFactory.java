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

import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePathResolver;
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
public class ContentAgnosticRuleKeyFactory
    extends ReflectiveRuleKeyFactory<RuleKeyBuilder<RuleKey>, RuleKey> {

  private final FileHashLoader fileHashLoader;
  private final SourcePathResolver pathResolver;
  private final LoadingCache<RuleKeyAppendable, RuleKey> ruleKeyCache;

  public ContentAgnosticRuleKeyFactory(
      int seed,
      SourcePathResolver pathResolver) {
    super(seed);
    // Build the cache around the sub-rule-keys and their dep lists.
    ruleKeyCache = CacheBuilder.newBuilder().weakKeys().build(
        new CacheLoader<RuleKeyAppendable, RuleKey>() {
          @Override
          public RuleKey load(@Nonnull RuleKeyAppendable appendable) throws Exception {
            RuleKeyBuilder<RuleKey> subKeyBuilder = newBuilder();
            appendable.appendToRuleKey(subKeyBuilder);
            return subKeyBuilder.build();
          }
        });

    this.pathResolver = pathResolver;
    this.fileHashLoader = new FileHashLoader() {

      @Override
      public HashCode get(Path path) throws IOException {
        return HashCode.fromLong(0);
      }

      @Override
      public long getSize(Path path) throws IOException {
        return 0;
      }

      @Override
      public HashCode get(ArchiveMemberPath archiveMemberPath) throws IOException {
        throw new AssertionError();
      }
    };
  }

  private RuleKeyBuilder<RuleKey> newBuilder() {
    return new RuleKeyBuilder<RuleKey>(pathResolver, fileHashLoader) {
      @Override
      protected RuleKeyBuilder<RuleKey> setBuildRule(BuildRule rule) {
        return setSingleValue(ContentAgnosticRuleKeyFactory.this.build(rule));
      }

      @Override
      public RuleKeyBuilder<RuleKey> setAppendableRuleKey(
          String key,
          RuleKeyAppendable appendable) {
        RuleKey subKey = ruleKeyCache.getUnchecked(appendable);
        return setAppendableRuleKey(key, subKey);
      }

      @Override
      public RuleKey build() {
        return buildRuleKey();
      }

    };
  }

  @Override
  protected RuleKeyBuilder<RuleKey> newBuilder(final BuildRule rule) {
    return newBuilder();
  }
}
