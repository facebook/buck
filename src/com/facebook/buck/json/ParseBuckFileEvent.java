/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.json;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.google.common.base.Objects;

import java.nio.file.Path;

/**
 * Base class for events about parsing build files..
 */
public abstract class ParseBuckFileEvent extends AbstractBuckEvent {
  private final Path buckFilePath;

  protected ParseBuckFileEvent(EventKey eventKey, Path buckFilePath) {
    super(eventKey);
    this.buckFilePath = buckFilePath;
  }

  public Path getBuckFilePath() {
    return buckFilePath;
  }

  @Override
  public String getValueString() {
    return buckFilePath.toString();
  }

  public static Started started(Path buckFilePath) {
    return new Started(buckFilePath);
  }

  public static Finished finished(Started started, int numRules) {
    return new Finished(started, numRules);
  }

  public static class Started extends ParseBuckFileEvent {
    protected Started(Path buckFilePath) {
      super(EventKey.unique(), buckFilePath);
    }

    @Override
    public String getEventName() {
      return "ParseBuckFileStarted";
    }
  }

  public static class Finished extends ParseBuckFileEvent {
    private final int numRules;

    protected Finished(Started started, int numRules) {
      super(started.getEventKey(), started.getBuckFilePath());
      this.numRules = numRules;
    }

    @Override
    public String getEventName() {
      return "ParseBuckFileFinished";
    }

    public int getNumRules() {
      return numRules;
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }
      // Because super.equals compares the EventKey, getting here means that we've somehow managed
      // to create 2 Finished events for the same Started event.
      throw new UnsupportedOperationException("Multiple conflicting Finished events detected.");
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(super.hashCode(), numRules);
    }
  }
}
