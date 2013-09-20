/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.plugin.intellij.commands.event;

import com.facebook.buck.plugin.intellij.ui.ProgressNode;
import com.google.common.base.Preconditions;

public class RuleStart extends Event {

  private final String name;

  RuleStart(int timestamp, String buildId, int threadId, String name) {
    super(EventFactory.RULE_START, timestamp, buildId, threadId);
    this.name = Preconditions.checkNotNull(name);
  }

  public String getName() {
    return name;
  }

  public boolean matchesEndRule(RuleEnd ruleEnd) {
    Preconditions.checkNotNull(ruleEnd);
    return getThreadId() == ruleEnd.getThreadId() && getBuildId().equals(ruleEnd.getBuildId())
        && getName().equals(ruleEnd.getName());
  }

  public ProgressNode createTreeNode() {
    return new ProgressNode(ProgressNode.Type.BUILDING, getName(), this);
  }

}
