/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.log.views.JsonViews;
import com.fasterxml.jackson.annotation.JsonView;

public class ParsingEvent extends AbstractBuckEvent implements BroadcastEvent {
  private final String eventName;

  public ParsingEvent(EventKey eventKey, String eventName) {
    super(eventKey);
    this.eventName = eventName;
  }

  public static SymlinkInvalidation symlinkInvalidation(String path) {
    return new SymlinkInvalidation(path);
  }

  public static EnvVariableChange environmentalChange(String diff) {
    return new EnvVariableChange(diff);
  }

  @Override
  protected String getValueString() {
    return eventName;
  }

  @Override
  public String getEventName() {
    return eventName;
  }

  public static class SymlinkInvalidation extends ParsingEvent {
    @JsonView(JsonViews.MachineReadableLog.class)
    String path;

    public SymlinkInvalidation(String path) {
      super(EventKey.unique(), "SymlinkInvalidation");
      this.path = path;
    }

    public String getPath() {
      return path;
    }
  }

  public static class EnvVariableChange extends ParsingEvent {
    @JsonView(JsonViews.MachineReadableLog.class)
    private final String diff;

    public EnvVariableChange(String diff) {
      super(EventKey.unique(), "EnvVariableChange");
      this.diff = diff;
    }

    public String getDiff() {
      return diff;
    }
  }
}
