/*
 * Copyright 2016-present Facebook, Inc.
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

public abstract class DaemonEvent extends AbstractBuckEvent {
  private final String eventName;

  public DaemonEvent(EventKey eventKey, String eventName) {
    super(eventKey);
    this.eventName = eventName;
  }

  @Override
  protected String getValueString() {
    return eventName;
  }

  @Override
  public String getEventName() {
    return eventName;
  }

  public static NewDaemonInstance newDaemonInstance(String eventName) {
    return new NewDaemonInstance(eventName);
  }

  public static class NewDaemonInstance extends DaemonEvent {
    public NewDaemonInstance(String eventName) {
      super(EventKey.unique(), eventName);
    }
  }
}
