/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.step.Step;
import com.google.common.base.Preconditions;

/**
 * Token provided by the result of {@link BuildRule#build(BuildContext)}, demonstrating that the
 * associated {@link BuildRule} was built successfully.
 */
public class BuildRuleSuccess {

  private final BuildRule rule;
  private final Type type;

  public static enum Type {
    /** Built by executing the {@link Step}s for the rule. */
    BUILT_LOCALLY,

    /** Fetched via the {@link ArtifactCache}. */
    FETCHED_FROM_CACHE,

    /** Computed {@link RuleKey} matches the one on disk. */
    MATCHING_RULE_KEY,

    /**
     * Computed {@link RuleKey} without deps matches the one on disk <em>AND</em> the ABI key for
     * the deps matches the one on disk.
     */
    MATCHING_DEPS_ABI_AND_RULE_KEY_NO_DEPS,

    /** Created trivially, such as an {@link InputRule} or {@link ProjectConfigRule}. */
    BY_DEFINITION,
  }

  public BuildRuleSuccess(BuildRule rule, Type type) {
    this.rule = Preconditions.checkNotNull(rule);
    this.type = type;
  }

  public Type getType() {
    return type;
  }

  @Override
  public String toString() {
    return rule.getFullyQualifiedName();
  }
}
