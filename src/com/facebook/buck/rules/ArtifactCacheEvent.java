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

package com.facebook.buck.rules;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEvent;
import com.google.common.base.CaseFormat;
import com.google.common.base.Objects;

/**
 * Base class for events about build rules.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class ArtifactCacheEvent extends AbstractBuckEvent {
  public static enum Operation {
    CONNECT,
    FETCH,
    STORE,
  }

  private final Operation operation;

  protected ArtifactCacheEvent(Operation operation) {
    this.operation = operation;
  }

  @Override
  public String getValueString() {
    return operation.toString().toLowerCase();
  }

  @Override
  public boolean eventsArePair(BuckEvent event) {
    if (!(event instanceof ArtifactCacheEvent)) {
      return false;
    }

    return Objects.equal(getOperation(), ((ArtifactCacheEvent) event).getOperation());
  }

  @Override
  public int hashCode() {
    return operation.hashCode();
  }

  public Operation getOperation() {
    return operation;
  }

  public static Started started(Operation operation) {
    return new Started(operation);
  }

  public static Finished finished(Operation operation, boolean success) {
    return new Finished(operation, success);
  }

  public static class Started extends ArtifactCacheEvent {
    protected Started(Operation operation) {
      super(operation);
    }

    @Override
    protected String getEventName() {
      return String.format("Artifact%sCacheStarted",
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, getOperation().toString()));
    }
  }

  public static class Finished extends ArtifactCacheEvent {
    private final boolean success;

    protected Finished(Operation operation, boolean success) {
      super(operation);
      this.success = success;
    }

    public boolean isSuccess() {
      return success;
    }

    @Override
    protected String getEventName() {
      return String.format("Artifact%sCacheFinished",
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, getOperation().toString()));
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }

      Finished that = (Finished) o;
      return isSuccess() == that.isSuccess();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(getOperation(), isSuccess());
    }
  }

}
