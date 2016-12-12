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

import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.RuleKeyFactory;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;

import java.util.concurrent.ExecutionException;

public abstract class ReflectiveRuleKeyFactory<
    RULE_KEY_BUILDER extends RuleKeyBuilder<RULE_KEY>,
    RULE_KEY>
    implements RuleKeyFactory<RULE_KEY> {

  private static final Logger LOG = Logger.get(ReflectiveRuleKeyFactory.class);

  private final int seed;
  private final LoadingCache<Class<? extends BuildRule>, ImmutableCollection<AlterRuleKey>>
      knownFields;
  private final LoadingCache<BuildRule, RULE_KEY> knownRules;

  public ReflectiveRuleKeyFactory(int seed) {
    this.seed = seed;
    this.knownFields = CacheBuilder.newBuilder().build(new ReflectiveAlterKeyLoader());
    this.knownRules = CacheBuilder.newBuilder().weakKeys().build(
        new CacheLoader<BuildRule, RULE_KEY>() {
          @Override
          public RULE_KEY load(BuildRule key) throws Exception {
            return newInstance(key).build();
          }
        });
  }

  /**
   * @return sub-classes should override this to provide specialized {@link RuleKeyBuilder}s.
   */
  protected abstract RULE_KEY_BUILDER newBuilder(BuildRule rule);

  protected RULE_KEY_BUILDER newInstance(BuildRule buildRule) {
    RULE_KEY_BUILDER builder = newBuilder(buildRule);
    builder.setReflectively("buck.seed", seed);
    builder.setReflectively("name", buildRule.getBuildTarget().getFullyQualifiedName());
    // Keyed as "buck.type" rather than "type" in case a build rule has its own "type" argument.
    builder.setReflectively("buck.type", buildRule.getType());
    builder.setReflectively("buckVersionUid", BuckVersion.getVersion());

    // We currently cache items using their full buck-out path, so make sure this is reflected in
    // the rule key.
    builder.setReflectively(
        "buckOut",
        buildRule.getProjectFilesystem().getBuckPaths().getConfiguredBuckOut().toString());

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
    } catch (ExecutionException | RuntimeException e) {
      LOG.warn(e, "Error creating rule key for %s (%s)", buildRule, buildRule.getType());
      throw Throwables.propagate(e);
    }

    return builder;
  }

  @Override
  public final RULE_KEY build(BuildRule buildRule) {
    try {
      return knownRules.getUnchecked(buildRule);
    } catch (RuntimeException e) {
      LOG.warn(e, "When building %s", buildRule);
      throw e;
    }
  }

}
