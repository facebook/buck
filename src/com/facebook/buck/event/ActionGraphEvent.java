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

import com.facebook.buck.core.model.actiongraph.ActionGraph;
import java.util.Optional;
import java.util.OptionalInt;

/** Base class for events about building up the action graph from the target graph. */
public abstract class ActionGraphEvent extends AbstractBuckEvent
    implements LeafEvent, WorkAdvanceEvent {

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

  public static Finished finished(Started started) {
    return new Finished(started, OptionalInt.empty(), Optional.empty());
  }

  public static Finished finished(Started started, int count) {
    return new Finished(started, OptionalInt.of(count), Optional.empty());
  }

  public static Finished finished(Started started, int count, ActionGraph actionGraph) {
    return new Finished(started, OptionalInt.of(count), Optional.of(actionGraph));
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

  public static class Finished extends ActionGraphEvent {
    private OptionalInt nodeCount;
    private Optional<ActionGraph> actionGraph;

    public Finished(Started started, OptionalInt count, Optional<ActionGraph> graph) {
      super(started.getEventKey());
      nodeCount = count;
      actionGraph = graph;
    }

    @Override
    public String getEventName() {
      return "BuildActionGraphFinished";
    }

    public OptionalInt getNodeCount() {
      return nodeCount;
    }

    public Optional<ActionGraph> getActionGraph() {
      return actionGraph;
    }
  }

  /** Event for incremental action graph construction. * */
  public static class IncrementalLoad extends ActionGraphEvent {
    public int reusedNodeCount;

    public IncrementalLoad(int reusedNodeCount) {
      super(EventKey.unique());
      this.reusedNodeCount = reusedNodeCount;
    }

    @Override
    public String getEventName() {
      return "ActionGraphIncrementalLoad";
    }

    public int getReusedNodeCount() {
      return reusedNodeCount;
    }
  }

  public static class Cache extends ActionGraphEvent implements BuckEvent {
    private final String eventName;

    public Cache(String eventName) {
      super(EventKey.unique());
      this.eventName = eventName;
    }

    public static Hit hit() {
      return new Hit();
    }

    public static Miss miss(boolean cacheWasEmpty) {
      return new Miss(cacheWasEmpty);
    }

    public static MissWithEmptyCache missWithEmptyCache() {
      return new MissWithEmptyCache();
    }

    public static MissWithTargetGraphHashMatch missWithTargetGraphHashMatch() {
      return new MissWithTargetGraphHashMatch();
    }

    public static MissWithTargetGraphDifference missWithTargetGraphDifference() {
      return new MissWithTargetGraphDifference();
    }

    public static class Hit extends Cache {
      public Hit() {
        super("ActionGraphCacheHit");
      }
    }

    public static class Miss extends Cache {
      public final boolean cacheWasEmpty;

      public Miss(boolean cacheWasEmpty) {
        super("ActionGraphCacheMiss");
        this.cacheWasEmpty = cacheWasEmpty;
      }
    }

    public static class MissWithEmptyCache extends Cache {
      public MissWithEmptyCache() {
        super("ActionGraphCacheMissWithEmptyCache");
      }
    }

    public static class MissWithTargetGraphHashMatch extends Cache {
      public MissWithTargetGraphHashMatch() {
        super("ActionGraphCacheMissWithTargetGraphHashMatch");
      }
    }

    public static class MissWithTargetGraphDifference extends Cache {
      public MissWithTargetGraphDifference() {
        super("ActionGraphCacheMissWithTargetGraphDifference");
      }
    }

    @Override
    public String getEventName() {
      return eventName;
    }
  }
}
