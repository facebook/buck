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

package com.facebook.buck.rules;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.LeafEvent;

/**
 * Base class for events about build rules.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class ArtifactCacheConnectEvent extends AbstractBuckEvent implements LeafEvent {

  @Override
  public String getCategory() {
    return "artifact_connect";
  }

  @Override
  public String getValueString() {
    return "";
  }

  @Override
  public boolean eventsArePair(BuckEvent event) {
    if (!(event instanceof ArtifactCacheEvent)) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }

  public static Started started() {
    return new Started();
  }

  public static Finished finished() {
    return new Finished();
  }

  public static class Started extends ArtifactCacheConnectEvent {
    @Override
    public String getEventName() {
      return "ArtifactCacheConnectStarted";
    }
  }

  public static class Finished extends ArtifactCacheConnectEvent {
    @Override
    public String getEventName() {
      return "ArtifactCacheConnectFinished";
    }
  }

}
