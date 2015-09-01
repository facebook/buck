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

package com.facebook.buck.json;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvent;

/**
 * Events posted before and after running buck.py.
 */
public abstract class ProjectBuildFileParseEvents extends AbstractBuckEvent implements LeafEvent {
  // This class does nothing; it exists only to group two AbstractBuckEvents.
  private ProjectBuildFileParseEvents(EventKey eventKey) {
    super(eventKey);
  }

  /**
   * Event posted immediately before launching buck.py to parse BUCK files.
   */
  public static class Started extends ProjectBuildFileParseEvents {
    public Started() {
      super(EventKey.unique());
    }

    @Override
    public String getEventName() {
      return "BuckFilesParseStarted";
    }

    @Override
    public String getCategory() {
      return "parse";
    }

    @Override
    protected String getValueString() {
      return "";
    }
  }

  /**
   * Event posted immediately after buck.py exits having parsed BUCK files.
   */
  public static class Finished extends ProjectBuildFileParseEvents {

    public Finished(Started started) {
      super(started.getEventKey());
    }

    @Override
    public String getEventName() {
      return "BuckFilesParseFinished";
    }

    @Override
    public String getCategory() {
      return "parse";
    }

    @Override
    protected String getValueString() {
      return "";
    }
  }
}
