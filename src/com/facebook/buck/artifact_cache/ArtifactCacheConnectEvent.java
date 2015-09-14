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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvent;

/**
 * Base class for events about build rules.
 */
public abstract class ArtifactCacheConnectEvent extends AbstractBuckEvent implements LeafEvent {

  public ArtifactCacheConnectEvent(EventKey eventKey) {
    super(eventKey);
  }

  @Override
  public String getCategory() {
    return "artifact_connect";
  }

  @Override
  public String getValueString() {
    return "";
  }

  public static Started started() {
    return new Started();
  }

  public static Finished finished(Started started) {
    return new Finished(started);
  }

  public static class Started extends ArtifactCacheConnectEvent {
    public Started() {
      super(EventKey.unique());
    }

    @Override
    public String getEventName() {
      return "ArtifactCacheConnectStarted";
    }
  }

  public static class Finished extends ArtifactCacheConnectEvent {

    public Finished(Started started) {
      super(started.getEventKey());
    }

    @Override
    public String getEventName() {
      return "ArtifactCacheConnectFinished";
    }
  }

}
