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

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

/**
 * Base class for events about parsing build files..
 */
public abstract class ParseEvent extends BuckEvent {
  private final ImmutableList<BuildTarget> buildTargets;

  protected ParseEvent(Iterable<BuildTarget> buildTargets) {
    this.buildTargets = ImmutableList.copyOf(Preconditions.checkNotNull(buildTargets));
  }

  public ImmutableList<BuildTarget> getBuildTargets() {
    return buildTargets;
  }

  @Override
  public String getValueString() {
    return Joiner.on(", ").join(buildTargets);
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof ParseEvent)) {
      return false;
    }

    ParseEvent that = (ParseEvent)o;

    return Objects.equal(getClass(), o.getClass()) &&
        Objects.equal(getBuildTargets(), that.getBuildTargets());
  }

  @Override
  public int hashCode() {
    return buildTargets.hashCode();
  }

  public static Started started(Iterable<BuildTarget> buildTargets) {
    return new Started(buildTargets);
  }

  public static Finished finished(Iterable<BuildTarget> buildTargets) {
    return new Finished(buildTargets);
  }

  public static class Started extends ParseEvent {
    protected Started(Iterable<BuildTarget> buildTargets) {
      super(buildTargets);
    }

    @Override
    protected String getEventName() {
      return "ParseStarted";
    }
  }

  public static class Finished extends ParseEvent {
    protected Finished(Iterable<BuildTarget> buildTargets) {
      super(buildTargets);
    }

    @Override
    protected String getEventName() {
      return "ParseFinished";
    }
  }
}
