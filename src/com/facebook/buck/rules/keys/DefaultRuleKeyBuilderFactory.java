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

import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.rules.AppendableRuleKeyCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.FileHashCache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;

import java.util.concurrent.ExecutionException;

public class DefaultRuleKeyBuilderFactory implements RuleKeyBuilderFactory {

  private final FileHashCache hashCache;
  private final SourcePathResolver pathResolver;
  private final LoadingCache<Class<? extends BuildRule>, ImmutableCollection<AlterRuleKey>>
      knownFields;
  private final AppendableRuleKeyCache appendableRuleKeyCache;

  public DefaultRuleKeyBuilderFactory(FileHashCache hashCache, SourcePathResolver pathResolver) {
    this.hashCache = hashCache;
    this.pathResolver = pathResolver;

    knownFields = CacheBuilder.newBuilder().build(new ReflectiveAlterKeyLoader());
    appendableRuleKeyCache = new AppendableRuleKeyCache(pathResolver, hashCache);
  }

  /**
   * @return a new {@link com.facebook.buck.rules.RuleKey.Builder} to be used throughout in this
   *     class.  Sub-classes can use this method to substitute in their own implementations.
   */
  @SuppressWarnings("unused")
  protected RuleKey.Builder newBuilder(
      BuildRule rule,
      SourcePathResolver pathResolver,
      FileHashCache hashCache,
      AppendableRuleKeyCache appendableRuleKeyCache) {
    return new RuleKey.Builder(
        pathResolver,
        hashCache,
        appendableRuleKeyCache);
  }

  @Override
  public RuleKey.Builder newInstance(BuildRule buildRule) {
    RuleKey.Builder builder =
        newBuilder(buildRule, pathResolver, hashCache, appendableRuleKeyCache);
    builder.setReflectively("name", buildRule.getBuildTarget().getFullyQualifiedName());
    // Keyed as "buck.type" rather than "type" in case a build rule has its own "type" argument.
    builder.setReflectively("buck.type", buildRule.getType());
    builder.setReflectively("buckVersionUid", BuckVersion.getVersion());

    if (buildRule instanceof RuleKeyAppendable) {
      // "." is not a valid first character for a field name, and so will never be seen in the
      // reflective rule key setting.
      builder.setReflectively(
          ".buck",
          appendableRuleKeyCache.get((RuleKeyAppendable) buildRule));
    }

    try {
      for (AlterRuleKey alterRuleKey : knownFields.get(buildRule.getClass())) {
        alterRuleKey.amendKey(builder, buildRule);
      }
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }

    return builder;
  }

}
