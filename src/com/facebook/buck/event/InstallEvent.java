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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.event.external.events.InstallFinishedEventExternalInterface;
import com.google.common.base.Objects;
import java.util.Optional;

public abstract class InstallEvent extends AbstractBuckEvent
    implements LeafEvent, WorkAdvanceEvent {
  private final BuildTarget buildTarget;

  protected InstallEvent(EventKey eventKey, BuildTarget buildTarget) {
    super(eventKey);
    this.buildTarget = buildTarget;
  }

  public BuildTarget getBuildTarget() {
    return buildTarget;
  }

  @Override
  public String getCategory() {
    return "install_apk";
  }

  @Override
  protected String getValueString() {
    return buildTarget.getFullyQualifiedName();
  }

  public static Started started(BuildTarget buildTarget) {
    return new Started(buildTarget);
  }

  public static Finished finished(
      Started started, boolean success, Optional<Long> pid, Optional<String> packageName) {
    return new Finished(started, success, pid, packageName);
  }

  public static class Started extends InstallEvent {
    protected Started(BuildTarget buildTarget) {
      super(EventKey.unique(), buildTarget);
    }

    @Override
    public String getEventName() {
      return INSTALL_STARTED;
    }
  }

  public static class Finished extends InstallEvent
      implements InstallFinishedEventExternalInterface {

    private static long invalidPid = -1;

    private final boolean success;
    private final long pid;
    private final String packageName;

    protected Finished(
        Started started, boolean success, Optional<Long> pid, Optional<String> packageName) {
      super(started.getEventKey(), started.getBuildTarget());
      this.success = success;
      this.pid = pid.orElse(invalidPid);
      this.packageName = packageName.orElse("");
    }

    @Override
    public boolean isSuccess() {
      return success;
    }

    public long getPid() {
      return pid;
    }

    @Override
    public String getPackageName() {
      return packageName;
    }

    @Override
    public String getEventName() {
      return INSTALL_FINISHED;
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
      return Objects.hashCode(super.hashCode(), isSuccess());
    }
  }
}
