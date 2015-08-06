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
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;

import java.util.concurrent.ExecutionException;

/**
 * A {@link RuleKeyBuilderFactory} which adds some default settings to {@link RuleKey}s.
 */
public abstract class AbstractRuleKeyBuilderFactory implements RuleKeyBuilderFactory {

  private final FileHashCache hashCache;
  private final SourcePathResolver pathResolver;
  private final LoadingCache<Class<? extends BuildRule>, ImmutableCollection<AlterRuleKey>>
      knownFields;

  public AbstractRuleKeyBuilderFactory(
      FileHashCache hashCache,
      SourcePathResolver pathResolver) {
    this.hashCache = hashCache;
    this.pathResolver = pathResolver;
    knownFields = CacheBuilder.newBuilder().build(new ReflectiveAlterKeyLoader());
  }

  protected abstract RuleKey.Builder newBuilder(
      SourcePathResolver pathResolver,
      FileHashCache hashCache,
      BuildRule rule);

  /**
   * Initialize a new {@link com.facebook.buck.rules.RuleKey.Builder}.
   */
  @Override
  public final RuleKey.Builder newInstance(BuildRule buildRule) {
    RuleKey.Builder builder = newBuilder(pathResolver, hashCache, buildRule);
    builder.setReflectively("name", buildRule.getBuildTarget().getFullyQualifiedName());
    // Keyed as "buck.type" rather than "type" in case a build rule has its own "type" argument.
    builder.setReflectively("buck.type", buildRule.getType());
    builder.setReflectively("buckVersionUid", BuckVersion.getVersion());

    if (buildRule instanceof RuleKeyAppendable) {
      // We call `setAppendableRuleKey` explicitly, since using `setReflectively` will try to add
      // the rule key of the `BuildRule`, which is what we're trying to calculate now.
      //
      // "." is not a valid first character for a field name, and so will never be seen in the
      // reflective rule key setting.
      builder.setAppendableRuleKey(".buck", (RuleKeyAppendable) buildRule);
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
