/*
 * Copyright 2014-present Facebook, Inc.
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

import com.google.common.collect.ImmutableMap;

/**
 * Weakly-typed events that should just show up in the trace log.
 */
public class TraceEvent extends AbstractBuckEvent {
  private final String name;
  private final ChromeTraceEvent.Phase phase;
  private final ImmutableMap<String, String> properties;

  public TraceEvent(
    String name,
    ChromeTraceEvent.Phase phase) {
    this(name, phase, ImmutableMap.<String, String>of());
  }

  public TraceEvent(
      String name,
      ChromeTraceEvent.Phase phase,
      ImmutableMap<String, String> properties) {
    this.name = name;
    this.phase = phase;
    this.properties = properties;
  }

  @Override
  public String getEventName() {
    return name;
  }

  @Override
  protected String getValueString() {
    return "";
  }

  public ChromeTraceEvent.Phase getPhase() {
    return phase;
  }

  public ImmutableMap<String, String> getProperties() {
    return properties;
  }
}
