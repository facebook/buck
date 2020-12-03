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

import com.google.common.collect.ImmutableMap;
import java.util.Map;

/** Base class for events being reported by plugins to in-process compilers such as JSR199 javac. */
public abstract class CompilerPluginDurationEvent extends SimplePerfEvent
    implements WorkAdvanceEvent {

  private final String buildTargetFullyQualifiedName;
  private final String pluginName;
  private final String durationName;
  private final ImmutableMap<String, String> args;

  protected CompilerPluginDurationEvent(
      EventKey eventKey,
      String buildTargetName,
      String pluginName,
      String durationName,
      ImmutableMap<String, String> args) {
    super(eventKey);
    this.buildTargetFullyQualifiedName = buildTargetName;
    this.pluginName = pluginName;
    this.durationName = durationName;
    this.args = args;
  }

  public String getBuildTargetFullyQualifiedName() {
    return buildTargetFullyQualifiedName;
  }

  public String getPluginName() {
    return pluginName;
  }

  public String getDurationName() {
    return durationName;
  }

  public ImmutableMap<String, String> getArgs() {
    return args;
  }

  @Override
  public PerfEventTitle getTitle() {
    return PerfEventTitle.of(getDurationName());
  }

  @Override
  public String getCategory() {
    return getPluginName();
  }

  @Override
  public ImmutableMap<String, Object> getEventInfo() {
    return getArgs().entrySet().stream()
        .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, e -> (Object) e.getValue()));
  }

  @Override
  protected String getValueString() {
    return "";
  }

  public static Started started(
      String buildTargetName,
      String pluginName,
      String durationName,
      ImmutableMap<String, String> args) {
    return new Started(buildTargetName, pluginName, durationName, args);
  }

  public static Finished finished(Started startedEvent, ImmutableMap<String, String> args) {
    return new Finished(startedEvent, args);
  }

  public static class Started extends CompilerPluginDurationEvent {

    public Started(
        String buildTargetName,
        String pluginName,
        String durationName,
        ImmutableMap<String, String> args) {
      super(EventKey.unique(), buildTargetName, pluginName, durationName, args);
    }

    @Override
    public String getEventName() {
      return String.format("%s.%sStarted", getPluginName(), getDurationName());
    }

    @Override
    public Type getEventType() {
      return Type.STARTED;
    }
  }

  public static class Finished extends CompilerPluginDurationEvent {

    public Finished(
        CompilerPluginDurationEvent.Started startedEvent, ImmutableMap<String, String> args) {
      super(
          startedEvent.getEventKey(),
          startedEvent.getBuildTargetFullyQualifiedName(),
          startedEvent.getPluginName(),
          startedEvent.getDurationName(),
          args);
    }

    @Override
    public String getEventName() {
      return String.format("%s.%sFinished", getPluginName(), getDurationName());
    }

    @Override
    public Type getEventType() {
      return Type.FINISHED;
    }
  }
}
