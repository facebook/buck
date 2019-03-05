/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.util.Scope;

public class LeafEvents {

  /** Creates a simple scoped leaf event that will be logged to superconsole, chrome traces, etc. */
  public static Scope scope(BuckEventBus eventBus, String category) {
    return scope(eventBus, category, true);
  }

  /**
   * @param eventBus
   * @param category the name of the category.
   * @param logToChromeTrace if it should be logged to the ChromeTrace or not.
   * @return
   */
  public static Scope scope(BuckEventBus eventBus, String category, boolean logToChromeTrace) {
    SimpleLeafEvent.Started started =
        new SimpleLeafEvent.Started(EventKey.unique(), category, logToChromeTrace);
    eventBus.post(started);
    return () -> eventBus.post(new SimpleLeafEvent.Finished(started));
  }

  public abstract static class SimpleLeafEvent extends AbstractBuckEvent implements LeafEvent {
    private final String category;
    private final boolean logToChromeTrace;

    private SimpleLeafEvent(EventKey eventKey, String category, boolean logToChromeTrace) {
      super(eventKey);
      this.category = category;
      this.logToChromeTrace = logToChromeTrace;
    }

    @Override
    public String getCategory() {
      return category;
    }

    @Override
    public String getEventName() {
      return category;
    }

    public boolean isLogToChromeTrace() {
      return logToChromeTrace;
    }

    public static class Finished extends SimpleLeafEvent {
      private Finished(Started started) {
        super(started.getEventKey(), started.getCategory(), started.isLogToChromeTrace());
      }

      @Override
      protected String getValueString() {
        return "Finished";
      }
    }

    public static class Started extends SimpleLeafEvent {
      private Started(EventKey eventKey, String category, boolean logToChromeTrace) {
        super(eventKey, category, logToChromeTrace);
      }

      @Override
      protected String getValueString() {
        return "Started";
      }
    }
  }
}
