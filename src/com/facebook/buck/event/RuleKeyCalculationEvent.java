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

/** Events used to track time spent calculating rule keys. */
public interface RuleKeyCalculationEvent extends LeafEvent, WorkAdvanceEvent {

  Type getType();

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

    public Event(EventKey eventKey, Type type) {
      super(eventKey);
      this.type = type;
    }

    @Override
    public Type getType() {
      return type;
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

    private DefaultStarted(EventKey eventKey, Type type) {
      super(eventKey, type);
    }
  }

  class DefaultFinished extends Event implements Finished {

    private DefaultFinished(EventKey eventKey, Type type) {
      super(eventKey, type);
    }
  }

  static Scope scope(BuckEventBus buckEventBus, Type type) {
    EventKey eventKey = EventKey.unique();
    buckEventBus.post(new DefaultStarted(eventKey, type));
    return () -> buckEventBus.post(new DefaultFinished(eventKey, type));
  }
}
