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
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;


/**
 * Base class for events about building up the action graph from the target graph.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class ActionGraphEvent extends AbstractBuckEvent implements LeafEvent {

  private final Iterable<BuildTarget> buildTargets;

  protected ActionGraphEvent(Iterable<BuildTarget> buildTargets) {
    this.buildTargets = Preconditions.checkNotNull(buildTargets);
  }

  @Override
  protected String getValueString() {
    return Joiner.on(", ").join(buildTargets);
  }

  @Override
  public String getCategory() {
    return "build_action_graph";
  }

  @Override
  public int hashCode() {
    return buildTargets.hashCode();
  }

  protected boolean isSimilar(ActionGraphEvent other) {
    return Objects.equal(buildTargets, other.buildTargets);
  }

  public static Started started(Iterable<BuildTarget> buildTargets) {
    return new Started(buildTargets);
  }

  public static Finished finished(Iterable<BuildTarget> buildTargets) {
    return new Finished(buildTargets);
  }

  public static class Started extends ActionGraphEvent {

    protected Started(Iterable<BuildTarget> buildTargets) {
      super(buildTargets);
    }

    @Override
    public boolean isRelatedTo(BuckEvent event) {
      return event instanceof ActionGraphEvent.Finished && isSimilar((ActionGraphEvent) event);
    }

    @Override
    public String getEventName() {
      return "BuildActionGraphStarted";
    }
  }

  public static class Finished extends ActionGraphEvent {

    protected Finished(Iterable<BuildTarget> buildTargets) {
      super(buildTargets);
    }

    @Override
    public boolean isRelatedTo(BuckEvent event) {
      return event instanceof ActionGraphEvent.Started && isSimilar((ActionGraphEvent) event);
    }

    @Override
    public String getEventName() {
      return "BuildActionGraphFinished";
    }
  }
}
