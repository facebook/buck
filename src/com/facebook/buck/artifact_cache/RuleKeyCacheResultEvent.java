/*
 * Copyright 2017-present Facebook, Inc.
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
package com.facebook.buck.artifact_cache;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;

public class RuleKeyCacheResultEvent extends AbstractBuckEvent {
  private final RuleKeyCacheResult ruleKeyCacheResult;

  public RuleKeyCacheResultEvent(RuleKeyCacheResult ruleKeyCacheResult) {
    super(EventKey.unique());
    this.ruleKeyCacheResult = ruleKeyCacheResult;
  }

  public RuleKeyCacheResult getRuleKeyCacheResult() {
    return ruleKeyCacheResult;
  }

  @Override
  public String getEventName() {
    return this.getClass().getName();
  }

  @Override
  protected String getValueString() {
    return getEventName() + getEventKey().toString();
  }
}
