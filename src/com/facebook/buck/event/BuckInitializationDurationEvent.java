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

/**
 * Event that contains the duration of time Buck took to perform initializations before command is
 * about to be executed, starting from call to main().
 */
public class BuckInitializationDurationEvent extends AbstractBuckEvent {

  private static final String EVENT_NAME = "initializationDurationEvent";

  private final long duration;

  /** @param duration Duration in milliseconds. */
  public BuckInitializationDurationEvent(long duration) {
    super(EventKey.unique());
    this.duration = duration;
  }

  /** @return Duration in milliseconds. */
  public long getDuration() {
    return duration;
  }

  @Override
  public String getEventName() {
    return EVENT_NAME;
  }

  @Override
  protected String getValueString() {
    return EVENT_NAME;
  }
}
