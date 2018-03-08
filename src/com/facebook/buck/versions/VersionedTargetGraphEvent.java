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

package com.facebook.buck.versions;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BroadcastEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.event.WorkAdvanceEvent;

/** Base class for events about building up the versioned target graph. */
public abstract class VersionedTargetGraphEvent extends AbstractBuckEvent
    implements LeafEvent, WorkAdvanceEvent {

  private VersionedTargetGraphEvent(EventKey eventKey) {
    super(eventKey);
  }

  @Override
  protected String getValueString() {
    return "";
  }

  @Override
  public String getCategory() {
    return "build_versioned_target_graph";
  }

  public static Started started() {
    return new Started();
  }

  public static Finished finished(Started started) {
    return new Finished(started);
  }

  public static Timeout timeout() {
    return new Timeout();
  }

  public static class Started extends VersionedTargetGraphEvent {

    private Started() {
      super(EventKey.unique());
    }

    @Override
    public String getEventName() {
      return "BuildVersionedTargetGraphStarted";
    }
  }

  public static class Finished extends VersionedTargetGraphEvent {

    private Finished(Started started) {
      super(started.getEventKey());
    }

    @Override
    public String getEventName() {
      return "BuildVersionedTargetGraphFinished";
    }
  }

  public static class Cache extends VersionedTargetGraphEvent implements BroadcastEvent {

    private final String eventName;

    private Cache(String eventName) {
      super(EventKey.unique());
      this.eventName = eventName;
    }

    public static Hit hit() {
      return new Hit();
    }

    public static Miss miss() {
      return new Miss();
    }

    public static class Hit extends Cache {
      private Hit() {
        super("VersionedGraphCacheHit");
      }
    }

    public static class Miss extends Cache {
      private Miss() {
        super("VersionedTargetGraphCacheMiss");
      }
    }

    @Override
    public String getEventName() {
      return eventName;
    }
  }

  public static class Timeout extends VersionedTargetGraphEvent {

    private Timeout() {
      super(EventKey.unique());
    }

    @Override
    public String getEventName() {
      return "VersionedTargetGraphTimedOut";
    }
  }
}
