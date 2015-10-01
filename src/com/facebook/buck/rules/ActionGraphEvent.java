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
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvent;


/**
 * Base class for events about building up the action graph from the target graph.
 */
public abstract class ActionGraphEvent extends AbstractBuckEvent implements LeafEvent {

  public ActionGraphEvent(EventKey eventKey) {
    super(eventKey);
  }

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

  public static Processed processed(int p, int t) {
    return new Processed(p, t);
  }

  public static Finished finished(Started started) {
    return new Finished(started);
  }

  public static class Started extends ActionGraphEvent {

    public Started() {
      super(EventKey.unique());
    }

    @Override
    public String getEventName() {
      return "BuildActionGraphStarted";
    }
  }

  public static class Processed extends ActionGraphEvent {

    private int processed;
    private int total;

    public Processed(int processed, int total) {
      super(EventKey.unique());
      this.processed = processed;
      this.total = total;
    }

    @Override
    public String getEventName() {
      return "BuildActionGraphProcessed";
    }

    @Override
    protected String getValueString() {
      return processed + " of " + total;
    }
  }

  public static class Finished extends ActionGraphEvent {

    public Finished(Started started) {
      super(started.getEventKey());
    }

    @Override
    public String getEventName() {
      return "BuildActionGraphFinished";
    }
  }
}
