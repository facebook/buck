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

package com.facebook.buck.event;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.util.Scope;

/** Events used to track time spent calculating rule keys. */
public interface RuleKeyCalculationEvent extends LeafEvent, WorkAdvanceEvent {

  Type getType();

  BuildTarget getTarget();

  @Override
  default String getCategory() {
    return getType().getCategory();
  }

  enum Type {
    NORMAL("rule_key_calc"),
    INPUT("input_rule_key_calc"),
    DEP_FILE("dep_file_rule_key_calc"),
    MANIFEST("manifest_rule_key_calc"),
    ;

    private final String category;

    Type(String category) {
      this.category = category;
    }

    public String getCategory() {
      return category;
    }
  }

  class Event extends AbstractBuckEvent implements RuleKeyCalculationEvent {

    private final Type type;
    private final BuildTarget target;

    public Event(EventKey eventKey, Type type, BuildTarget target) {
      super(eventKey);
      this.type = type;
      this.target = target;
    }

    @Override
    public Type getType() {
      return type;
    }

    @Override
    public BuildTarget getTarget() {
      return target;
    }

    @Override
    public String getEventName() {
      return RuleKeyCalculationEvent.class.getSimpleName();
    }

    @Override
    protected String getValueString() {
      return type.toString();
    }
  }

  interface Started extends RuleKeyCalculationEvent {}

  interface Finished extends RuleKeyCalculationEvent {}

  class DefaultStarted extends Event implements Started {

    private DefaultStarted(EventKey eventKey, Type type, BuildTarget target) {
      super(eventKey, type, target);
    }
  }

  class DefaultFinished extends Event implements Finished {

    private DefaultFinished(EventKey eventKey, Type type, BuildTarget target) {
      super(eventKey, type, target);
    }
  }

  static Scope scope(BuckEventBus buckEventBus, Type type, BuildTarget target) {
    EventKey eventKey = EventKey.unique();
    buckEventBus.post(new DefaultStarted(eventKey, type, target));
    return () -> buckEventBus.post(new DefaultFinished(eventKey, type, target));
  }
}
