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
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class ParseBuckFileEvent extends AbstractBuckEvent {
  private final Path buckFilePath;

  protected ParseBuckFileEvent(Path buckFilePath) {
    super(EventKey.of("ParseBuckFileEvent", buckFilePath));
    this.buckFilePath = buckFilePath;
  }

  public Path getBuckFilePath() {
    return buckFilePath;
  }

  @Override
  public String getValueString() {
    return buckFilePath.toString();
  }

  @Override
  public int hashCode() {
    return buckFilePath.hashCode();
  }

  public static Started started(Path buckFilePath) {
    return new Started(buckFilePath);
  }

  public static Finished finished(Path buckFilePath, int numRules) {
    return new Finished(buckFilePath, numRules);
  }

  public static class Started extends ParseBuckFileEvent {
    protected Started(Path buckFilePath) {
      super(buckFilePath);
    }

    @Override
    public String getEventName() {
      return "ParseBuckFileStarted";
    }
  }

  public static class Finished extends ParseBuckFileEvent {
    private final int numRules;

    protected Finished(Path buckFilePath, int numRules) {
      super(buckFilePath);
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
    public boolean equals(Object obj) {
      if (!(super.equals(obj))) {
        return false;
      }

      Finished that = (Finished) obj;
      return numRules == that.getNumRules();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(getBuckFilePath(), numRules);
    }
  }
}
