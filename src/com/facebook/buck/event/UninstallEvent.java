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

package com.facebook.buck.event;

import com.google.common.base.Objects;

public abstract class UninstallEvent extends AbstractBuckEvent implements LeafEvent {
  private final String packageName;

  protected UninstallEvent(EventKey eventKey, String packageName) {
    super(eventKey);
    this.packageName = packageName;
  }

  public String getPackageName() {
    return packageName;
  }

  @Override
  public String getCategory() {
    return "uninstall_apk";
  }

  @Override
  protected String getValueString() {
    return packageName;
  }

  public static Started started(String packageName) {
    return new Started(packageName);
  }

  public static Finished finished(Started started, boolean success) {
    return new Finished(started, success);
  }

  public static class Started extends UninstallEvent {
    protected Started(String packageName) {
      super(EventKey.unique(), packageName);
    }

    @Override
    public String getEventName() {
      return "UninstallStarted";
    }
  }

  public static class Finished extends UninstallEvent {
    private final boolean success;

    protected Finished(Started started, boolean success) {
      super(started.getEventKey(), started.getPackageName());
      this.success = success;
    }

    public boolean isSuccess() {
      return success;
    }

    @Override
    public String getEventName() {
      return "UninstallFinished";
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
      return Objects.hashCode(super.hashCode(), success);
    }
  }
}
