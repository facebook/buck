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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public class FakeRuleKeyFactory
    implements RuleKeyFactoryWithDiagnostics<RuleKey>, DependencyFileRuleKeyFactory {

  private final ImmutableMap<BuildTarget, RuleKey> ruleKeys;
  private final ImmutableSet<BuildTarget> oversized;

  public FakeRuleKeyFactory(
      ImmutableMap<BuildTarget, RuleKey> ruleKeys, ImmutableSet<BuildTarget> oversized) {
    this.ruleKeys = ruleKeys;
    this.oversized = oversized;
  }

  public FakeRuleKeyFactory(ImmutableMap<BuildTarget, RuleKey> ruleKeys) {
    this(ruleKeys, ImmutableSet.of());
  }

  @Override
  public RuleKey build(BuildRule buildRule) {
    if (oversized.contains(buildRule.getBuildTarget())) {
      throw new SizeLimiter.SizeLimitException();
    }
    return ruleKeys.get(buildRule.getBuildTarget());
  }

  @Override
  public RuleKeyAndInputs build(
      SupportsDependencyFileRuleKey rule, ImmutableList<DependencyFileEntry> inputs) {
    return RuleKeyAndInputs.of(build(rule), ImmutableSet.of());
  }

  @Override
  public RuleKeyAndInputs buildManifestKey(SupportsDependencyFileRuleKey rule) {
    return RuleKeyAndInputs.of(build(rule), ImmutableSet.of());
  }

  @Override
  public <DIAG_KEY> RuleKeyDiagnostics.Result<RuleKey, DIAG_KEY> buildForDiagnostics(
      BuildRule rule, RuleKeyHasher<DIAG_KEY> hasher) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <DIAG_KEY> RuleKeyDiagnostics.Result<RuleKey, DIAG_KEY> buildForDiagnostics(
      AddsToRuleKey appendable, RuleKeyHasher<DIAG_KEY> hasher) {
    throw new UnsupportedOperationException();
  }
}
