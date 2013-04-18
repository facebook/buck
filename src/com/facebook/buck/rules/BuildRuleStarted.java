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

import com.google.common.base.Objects;

public class BuildRuleStarted extends BuildEvent {
  private final BuildRule rule;

  public BuildRuleStarted(BuildRule rule) {
    this.rule = rule;
  }

  public BuildRule getBuildRule() {
    return rule;
  }

  @Override
  public String toString() {
    // Share the formatting style of BuildRuleFinished
    return String.format("BuildRuleStarted(%s)", rule);
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || (!(o instanceof BuildRuleStarted))) {
      return false;
    }

    return Objects.equal(BuildRuleStarted.class, o.getClass()) &&
        Objects.equal(getBuildRule(), ((BuildRuleStarted)o).getBuildRule());
  }

  @Override
  public int hashCode() {
    return rule.hashCode();
  }
}
