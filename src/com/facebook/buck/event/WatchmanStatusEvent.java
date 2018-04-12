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

public abstract class WatchmanStatusEvent extends AbstractBuckEvent implements BuckEvent {
  private final String eventName;

  public WatchmanStatusEvent(EventKey eventKey, String eventName) {
    super(eventKey);
    this.eventName = eventName;
  }

  @Override
  protected String getValueString() {
    return eventName;
  }

  @Override
  public String getEventName() {
    return eventName;
  }

  public static Started started() {
    return new Started();
  }

  public static Finished finished() {
    return new Finished();
  }

  public static Overflow overflow(String reason) {
    return new Overflow(reason);
  }

  public static FileCreation fileCreation(String filename) {
    return new FileCreation(filename);
  }

  public static FileDeletion fileDeletion(String filename) {
    return new FileDeletion(filename);
  }

  public static ZeroFileChanges zeroFileChanges() {
    return new ZeroFileChanges();
  }

  public static class Started extends WatchmanStatusEvent {
    public Started() {
      super(EventKey.unique(), "WatchmanStarted");
    }
  }

  public static class Finished extends WatchmanStatusEvent {
    public Finished() {
      super(EventKey.unique(), "WatchmanFinished");
    }
  }

  public static class Overflow extends WatchmanStatusEvent {
    @JsonView(JsonViews.MachineReadableLog.class)
    private String reason;

    public Overflow(String reason) {
      super(EventKey.unique(), "WatchmanOverflow");
      this.reason = reason;
    }

    public String getReason() {
      return reason;
    }
  }

  public static class FileCreation extends WatchmanStatusEvent {
    @JsonView(JsonViews.MachineReadableLog.class)
    private String filename;

    public FileCreation(String filename) {
      super(EventKey.unique(), "WatchmanFileCreation");
      this.filename = filename;
    }

    public String getFilename() {
      return this.filename;
    }
  }

  public static class FileDeletion extends WatchmanStatusEvent {
    @JsonView(JsonViews.MachineReadableLog.class)
    private String filename;

    public FileDeletion(String filename) {
      super(EventKey.unique(), "WatchmanFileDeletion");
      this.filename = filename;
    }

    public String getFilename() {
      return this.filename;
    }
  }

  /** This event is to be posted when Watchman does not report any altered files. */
  public static class ZeroFileChanges extends WatchmanStatusEvent {
    public ZeroFileChanges() {
      super(EventKey.unique(), "WatchmanZeroFileChanges");
    }
  }
}
