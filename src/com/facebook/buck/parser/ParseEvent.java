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
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ActionGraph;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * Base class for events about parsing build files..
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class ParseEvent extends AbstractBuckEvent implements LeafEvent {
  private final ImmutableList<BuildTarget> buildTargets;

  protected ParseEvent(Iterable<BuildTarget> buildTargets) {
    this.buildTargets = ImmutableList.copyOf(Preconditions.checkNotNull(buildTargets));
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

  @Override
  public boolean eventsArePair(BuckEvent event) {
    if (!(event instanceof ParseEvent)) {
      return false;
    }

    ParseEvent that = (ParseEvent) event;

    return Objects.equal(getBuildTargets(), that.getBuildTargets());
  }

  @Override
  public int hashCode() {
    return buildTargets.hashCode();
  }

  public static Started started(Iterable<BuildTarget> buildTargets) {
    return new Started(buildTargets);
  }

  public static Finished finished(Iterable<BuildTarget> buildTargets,
      Optional<ActionGraph> graph) {
    return new Finished(buildTargets, graph);
  }

  public static class Started extends ParseEvent {
    protected Started(Iterable<BuildTarget> buildTargets) {
      super(buildTargets);
    }

    @Override
    public String getEventName() {
      return "ParseStarted";
    }
  }

  public static class Finished extends ParseEvent {
    /** If this is {@link Optional#absent()}, then the parse did not complete successfully. */
    private final Optional<ActionGraph> graph;

    protected Finished(Iterable<BuildTarget> buildTargets, Optional<ActionGraph> graph) {
      super(buildTargets);
      this.graph = Preconditions.checkNotNull(graph);
    }

    @Override
    public String getEventName() {
      return "ParseFinished";
    }

    @JsonIgnore
    public Optional<ActionGraph> getGraph() {
      return graph;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(super.equals(obj))) {
        return false;
      }

      Finished that = (Finished) obj;
      return Objects.equal(this.getGraph(), that.getGraph());
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(getBuildTargets(), getGraph());
    }
  }
}
