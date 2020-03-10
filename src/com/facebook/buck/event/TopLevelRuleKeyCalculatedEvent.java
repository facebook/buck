/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.event;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;

/**
 * This event is posted to {@link com.facebook.buck.event.listener.RuleKeyCheckListener} just after
 * the rule key is calculated and before the top level target is actually built.
 */
public class TopLevelRuleKeyCalculatedEvent extends AbstractBuckEvent {
  private RuleKey rulekey;
  private BuildTarget buildTarget;

  public TopLevelRuleKeyCalculatedEvent(BuildTarget buildTarget, RuleKey rulekey) {
    super(EventKey.slowValueKey(buildTarget.getFullyQualifiedName(), rulekey.toString()));
    this.rulekey = rulekey;
    this.buildTarget = buildTarget;
  }

  @Override
  public String getEventName() {
    return "TopLevelRuleKeyCalculatedEvent";
  }

  @Override
  protected String getValueString() {
    return String.format("%s:%s", getFullyQualifiedRuleName(), getRulekeyAsString());
  }

  public RuleKey getRulekey() {
    return rulekey;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  public String getRulekeyAsString() {
    return rulekey.toString();
  }

  public String getFullyQualifiedRuleName() {
    return buildTarget.getFullyQualifiedName();
  }
}
