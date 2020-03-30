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

package com.facebook.buck.core.build.event;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;

/** Event to signal the end of finalize BuildRule stage during building of a rule. */
public class FinalizingBuildRuleEvent extends AbstractBuckEvent {

  private final BuildRule buildRule;

  private FinalizingBuildRuleEvent(EventKey eventKey, BuildRule buildRule) {
    super(eventKey);
    this.buildRule = buildRule;
  }

  public BuildRule getBuildRule() {
    return buildRule;
  }

  @Override
  public String getEventName() {
    return FinalizingBuildRuleEvent.class.getSimpleName();
  }

  @Override
  protected String getValueString() {
    return getBuildRule().getFullyQualifiedName();
  }

  /** Posts event of type FinalizingBuildRuleEvent into {@link BuckEventBus} */
  public static void postEvent(BuckEventBus buckEventBus, BuildRule buildRule) {
    buckEventBus.post(new FinalizingBuildRuleEvent(EventKey.unique(), buildRule));
  }
}
