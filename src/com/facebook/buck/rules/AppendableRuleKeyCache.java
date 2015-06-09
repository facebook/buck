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

package com.facebook.buck.rules;

import com.facebook.buck.util.FileHashCache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

public class AppendableRuleKeyCache {

  private final SourcePathResolver pathResolver;
  private final FileHashCache fileHashCache;
  private final LoadingCache<RuleKeyAppendable, RuleKey> cache;

  public AppendableRuleKeyCache(SourcePathResolver pathResolver, FileHashCache fileHashCache) {
    this.pathResolver = pathResolver;
    this.fileHashCache = fileHashCache;

    this.cache = CacheBuilder.newBuilder().weakKeys().build(
        new CacheLoader<RuleKeyAppendable, RuleKey>() {
          @Override
          public RuleKey load(RuleKeyAppendable appendable) throws Exception {
            return getAppendableRuleKey(appendable);
          }
        });
  }

  public RuleKey get(RuleKeyAppendable appendable) {
    return cache.getUnchecked(appendable);
  }

  private RuleKey getAppendableRuleKey(
      RuleKeyAppendable appendable) {
    RuleKey.Builder subKeyBuilder =
        new RuleKey.Builder(
            pathResolver,
            fileHashCache,
            this);
    appendable.appendToRuleKey(subKeyBuilder);
    return subKeyBuilder.build();
  }

}
