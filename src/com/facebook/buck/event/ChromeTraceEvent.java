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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

/**
 * Json format for Chrome Trace events that can be viewed in chrome://tracing.
 * See https://github.com/google/trace-viewer for more information.
 */
@JsonInclude(Include.NON_NULL)
public class ChromeTraceEvent {
  public enum Phase {
    BEGIN("B"),
    END("E"),
    IMMEDIATE("I"),
    COUNTER("C"),
    ASYNC_START("S"),
    ASYNC_FINISH("F"),
    OBJECT_SNAPSHOT("O"),
    OBJECT_NEW("N"),
    OBJECT_DELETE("D"),
    METADATA("M");

    private final String phase;

    Phase(String phase) {
      this.phase = phase;
    }

    @JsonValue
    String getPhase() {
      return phase;
    }
  }

  private final String category;
  private final String name;
  private final Phase phase;
  private final long processId;
  private final long threadId;
  private final long microTime;
  private final ImmutableMap<String, String> args;

  public ChromeTraceEvent(@JsonProperty("cat") String category,
                          @JsonProperty("name") String name,
                          @JsonProperty("ph") Phase phase,
                          @JsonProperty("pid") long processId,
                          @JsonProperty("tid") long threadId,
                          @JsonProperty("ts") long microTime,
                          @JsonProperty("args") Map<String, String> args) {
    this.category = category;
    this.name = name;
    this.phase = phase;
    this.processId = processId;
    this.threadId = threadId;
    this.microTime = microTime;
    this.args = ImmutableMap.copyOf(args);
  }

  @JsonProperty("name")
  public String getName() {
    return name;
  }

  @JsonProperty("ph")
  public Phase getPhase() {
    return phase;
  }

  @JsonProperty("pid")
  public long getProcessId() {
    return processId;
  }

  @JsonProperty("tid")
  public long getThreadId() {
    return threadId;
  }

  @JsonProperty("ts")
  public long getMicroTime() {
    return microTime;
  }

  @JsonProperty("args")
  public Map<String, String> getArgs() {
    return args;
  }

  @JsonProperty("cat")
  public String getCategory() {
    return category;
  }
}
