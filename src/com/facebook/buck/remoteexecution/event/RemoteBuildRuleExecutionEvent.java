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

package com.facebook.buck.remoteexecution.event;

import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;

/** Event to signal the end of building a rule remotely. */
public class RemoteBuildRuleExecutionEvent extends AbstractBuckEvent {

  private final BuildRule buildRule;
  private final long executionDurationMs;

  private RemoteBuildRuleExecutionEvent(
      EventKey eventKey, BuildRule buildRule, long executionDurationMs) {
    super(eventKey);
    this.buildRule = buildRule;
    this.executionDurationMs = executionDurationMs;
  }

  public BuildRule getBuildRule() {
    return buildRule;
  }

  public long getExecutionDurationMs() {
    return executionDurationMs;
  }

  @Override
  public String getEventName() {
    return RemoteBuildRuleExecutionEvent.class.getSimpleName();
  }

  @Override
  protected String getValueString() {
    return getBuildRule().getFullyQualifiedName() + getExecutionDurationMs();
  }

  /** Posts event of type RemoteBuildRuleExecutionEvent into {@link BuckEventBus} */
  public static void postEvent(
      BuckEventBus buckEventBus, BuildRule buildRule, long executionDurationMs) {
    buckEventBus.post(
        new RemoteBuildRuleExecutionEvent(EventKey.unique(), buildRule, executionDurationMs));
  }

  public static RemoteBuildRuleExecutionEvent createEvent(
      BuildRule buildRule, long executionDurationMs) {
    return new RemoteBuildRuleExecutionEvent(EventKey.unique(), buildRule, executionDurationMs);
  }
}
