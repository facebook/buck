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

/**
 * Used to tag a rule that supports input-based rule keys.
 *
 * <p>{@link com.facebook.buck.rules.BuildRule}s implementing this interface will cause rule key to
 * be computed by enumerating their dependencies implicitly through their inputs, which are
 * described by {@link com.facebook.buck.rules.SourcePath}s added to their {@link
 * com.facebook.buck.rules.RuleKey}.
 *
 * <p>Input-based rule keys are generally more accurate than normal rule keys, as they won't
 * necessarily change if the rule key of a dependency changed. Instead, they only change if a the
 * actual inputs to the rule change.
 *
 * @see InputBasedRuleKeyFactory
 */
public interface SupportsInputBasedRuleKey extends BuildRule {
  default boolean inputBasedRuleKeyIsEnabled() {
    return true;
  }

  static boolean isSupported(BuildRule rule) {
    return (rule instanceof SupportsInputBasedRuleKey)
        && ((SupportsInputBasedRuleKey) rule).inputBasedRuleKeyIsEnabled();
  }
}
