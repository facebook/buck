/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.google.common.collect.ImmutableMap;

/**
 * Base class for events being reported by plugins to in-process compilers such as JSR199 javac.
 */
public abstract class CompilerPluginDurationEvent extends AbstractBuckEvent implements LeafEvent {
  private final BuildTarget buildTarget;
  private final String pluginName;
  private final String durationName;
  private final ImmutableMap<String, String> args;

  protected CompilerPluginDurationEvent(
      BuildTarget buildTarget,
      String pluginName,
      String durationName,
      ImmutableMap<String, String> args) {
    this.buildTarget = buildTarget;
    this.pluginName = pluginName;
    this.durationName = durationName;
    this.args = args;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
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
  protected String getValueString() {
    return "";
  }

  @Override
  public String getCategory() {
    return pluginName + "." + durationName;
  }

  @Override
  public boolean isRelatedTo(BuckEvent event) {
    if (!(event instanceof CompilerPluginDurationEvent)) {
      return false;
    }

    if (event == this) {
      return true;
    }

    if (event instanceof Finished && ((Finished) event).getStartedEvent() == this) {
      return true;
    }

    if (this instanceof Finished && ((Finished) this).getStartedEvent() == event) {
      return true;
    }

    return false;
  }

  public static Started started(
      BuildTarget buildTarget,
      String pluginName,
      String durationName,
      ImmutableMap<String, String> args) {
    return new Started(buildTarget, pluginName, durationName, args);
  }

  public static Finished finished(
      Started startedEvent,
      ImmutableMap<String, String> args) {
    return new Finished(startedEvent, args);
  }

  public static class Started extends CompilerPluginDurationEvent {
    public Started(
        BuildTarget buildTarget,
        String pluginName,
        String durationName,
        ImmutableMap<String, String> args) {
      super(buildTarget, pluginName, durationName, args);
    }

    @Override
    public String getEventName() {
      return String.format("%s.%sStarted", getPluginName(), getDurationName());
    }
  }

  public static class Finished extends CompilerPluginDurationEvent {
    private final Started startedEvent;

    public Finished(
        Started startedEvent,
        ImmutableMap<String, String> args) {
      super(
          startedEvent.getBuildTarget(),
          startedEvent.getPluginName(),
          startedEvent.getDurationName(),
          args);
      this.startedEvent = startedEvent;
    }

    public Started getStartedEvent() {
      return startedEvent;
    }

    @Override
    public String getEventName() {
      return String.format("%s.%sFinished", getPluginName(), getDurationName());
    }
  }
}
