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

package com.facebook.buck.parser;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

/**
 * Base class for events about parsing build files..
 */
public abstract class ParseEvent extends AbstractBuckEvent implements LeafEvent {
  private final ImmutableList<BuildTarget> buildTargets;

  protected ParseEvent(EventKey eventKey, Iterable<BuildTarget> buildTargets) {
    super(eventKey);
    this.buildTargets = ImmutableList.copyOf(buildTargets);
  }

  public ImmutableList<BuildTarget> getBuildTargets() {
    return buildTargets;
  }

  @Override
  @JsonIgnore
  public String getCategory() {
    return "parse";
  }

  @Override
  public String getValueString() {
    return Joiner.on(", ").join(buildTargets);
  }

  public static Started started(Iterable<BuildTarget> buildTargets) {
    return new Started(buildTargets);
  }

  public static Finished finished(Started started,
      Optional<TargetGraph> graph) {
    return new Finished(started, graph);
  }

  public static class Started extends ParseEvent {
    protected Started(Iterable<BuildTarget> buildTargets) {
      super(EventKey.unique(), buildTargets);
    }

    @Override
    public String getEventName() {
      return "ParseStarted";
    }
  }

  public static class Finished extends ParseEvent {
    /** If this is {@link Optional#absent()}, then the parse did not complete successfully. */
    private final Optional<TargetGraph> graph;

    protected Finished(Started started, Optional<TargetGraph> graph) {
      super(started.getEventKey(), started.getBuildTargets());
      this.graph = graph;
    }

    @Override
    public String getEventName() {
      return "ParseFinished";
    }

    @JsonIgnore
    public Optional<TargetGraph> getGraph() {
      return graph;
    }
  }
}
