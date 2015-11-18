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
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;

import java.util.concurrent.ExecutionException;

public abstract class ReflectiveRuleKeyBuilderFactory<T extends RuleKeyBuilder>
    implements RuleKeyBuilderFactory {

  private final LoadingCache<Class<? extends BuildRule>, ImmutableCollection<AlterRuleKey>>
      knownFields;

  public ReflectiveRuleKeyBuilderFactory() {
    knownFields = CacheBuilder.newBuilder().build(new ReflectiveAlterKeyLoader());
  }

  /**
   * @return sub-classes should override this to provide specialized {@link RuleKeyBuilder}s.
   */
  protected abstract T newBuilder(BuildRule rule);

  @Override
  public T newInstance(BuildRule buildRule) {
    T builder = newBuilder(buildRule);
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

  @Override
  public final RuleKey build(BuildRule buildRule) {
    return newInstance(buildRule).build();
  }

}
