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

package com.facebook.buck.rules;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.LeafEvent;


/**
 * Base class for events about building up the action graph from the target graph.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class ActionGraphEvent extends AbstractBuckEvent implements LeafEvent {

  @Override
  protected String getValueString() {
    return "";
  }

  @Override
  public String getCategory() {
    return "build_action_graph";
  }

  public static Started started() {
    return new Started();
  }

  public static Finished finished(Started started) {
    return new Finished(started);
  }

  public static class Started extends ActionGraphEvent {

    @Override
    public String getEventName() {
      return "BuildActionGraphStarted";
    }
  }

  public static class Finished extends ActionGraphEvent {

    public Finished(Started started) {
      chain(started);
    }

    @Override
    public String getEventName() {
      return "BuildActionGraphFinished";
    }
  }
}
