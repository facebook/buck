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
package com.facebook.buck.event;

/**
 * Event to tell the console that it needs to flush now. This is used to ensure that all pending
 * events are written before potentially writing to stdout (which would cause certain event busses
 * to cease rendering)
 *
 * <p>Use of this class is generally an antipattern, and is only used currently to make up for some
 * shortcomings in {@link com.facebook.buck.event.listener.SuperConsoleEventBusListener}
 */
public class FlushConsoleEvent extends AbstractBuckEvent {

  public FlushConsoleEvent() {
    super(EventKey.unique());
  }

  @Override
  protected String getValueString() {
    return "flush";
  }

  @Override
  public String getEventName() {
    return "FlushConsoleEvent";
  }
}
