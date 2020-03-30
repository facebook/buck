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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.util.Scope;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;

/** Events used to track time spent executing rule. */
public interface BuildRuleExecutionEvent extends BuckEvent {

  BuildTarget getTarget();

  /** Common event implementation */
  class Event extends AbstractBuckEvent implements BuildRuleExecutionEvent {

    private final BuildRule buildRule;
    private final String name;

    public Event(EventKey eventKey, BuildRule buildRule) {
      super(eventKey);
      this.buildRule = buildRule;
      this.name = buildRule.getFullyQualifiedName();
    }

    @Override
    public BuildTarget getTarget() {
      return buildRule.getBuildTarget();
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public BuildRule getBuildRule() {
      return buildRule;
    }

    @Override
    @JsonIgnore
    public String getEventName() {
      return BuildRuleExecutionEvent.class.getSimpleName();
    }

    @Override
    @JsonIgnore
    protected String getValueString() {
      return name;
    }
  }

  /** Started event that implements BuildRuleExecutionEvent interface */
  class Started extends Event implements BuildRuleExecutionEvent {

    private Started(EventKey eventKey, BuildRule buildRule) {
      super(eventKey, buildRule);
    }
  }

  /** Finished event that implements BuildRuleExecutionEvent interface */
  class Finished extends Event implements BuildRuleExecutionEvent {

    private final long statEventNanoTime;

    public Finished(EventKey eventKey, BuildRule buildRule, long statEventNanoTime) {
      super(eventKey, buildRule);
      this.statEventNanoTime = statEventNanoTime;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public long getElapsedTimeNano() {
      return getNanoTime() - statEventNanoTime;
    }
  }

  /** Returns Scope with start/finish events of type BuildRuleExecutionEvent */
  static Scope scope(BuckEventBus buckEventBus, BuildRule buildRule) {
    EventKey eventKey = EventKey.unique();

    Started statEvent = new Started(eventKey, buildRule);
    // nano time is available only after posting event to event bus
    buckEventBus.post(statEvent);
    return () -> buckEventBus.post(new Finished(eventKey, buildRule, statEvent.getNanoTime()));
  }
}
