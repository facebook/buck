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

public abstract class WatchmanStatusEvent extends AbstractBuckEvent {
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

  public static Overflow overflow(String reason) {
    return new Overflow(reason);
  }

  public static FileCreation fileCreation() {
    return new FileCreation();
  }

  public static FileDeletion fileDeletion() {
    return new FileDeletion();
  }

  public static class Overflow extends WatchmanStatusEvent {
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
    public FileCreation() {
      super(EventKey.unique(), "WatchmanFileCreation");
    }
  }

  public static class FileDeletion extends WatchmanStatusEvent {
    public FileDeletion() {
      super(EventKey.unique(), "WatchmanFileDeletion");
    }
  }
}
