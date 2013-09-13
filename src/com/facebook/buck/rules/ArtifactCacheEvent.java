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
import com.facebook.buck.event.LeafEvent;
import com.google.common.base.CaseFormat;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;

/**
 * Base class for events about build rules.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class ArtifactCacheEvent extends AbstractBuckEvent implements LeafEvent {
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
  public String getCategory() {
    return "artifact_" + getValueString();
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

  public static Finished finished(Operation operation) {
    return new Finished(operation);
  }

  public static Finished finished(Operation operation, CacheResult cacheResult) {
    Preconditions.checkNotNull(cacheResult);
    return new Finished(operation, cacheResult);
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
    /** {@code null} iff {@link #getOperation()} is not {@link Operation#FETCH}. */
    @Nullable
    private final CacheResult cacheResult;

    protected Finished(Operation operation) {
      this(operation, /* cacheResult */ null);
    }

    protected Finished(Operation operation, @Nullable CacheResult cacheResult) {
      super(operation);
      Preconditions.checkArgument(
          (operation.equals(Operation.FETCH) && cacheResult != null) ||
          (!operation.equals(Operation.FETCH) && cacheResult == null),
          "For FETCH operations, cacheResult must be non-null. " +
          "For non-FETCH operations, cacheResult must be null.");
      this.cacheResult = cacheResult;
    }

    public boolean isSuccess() {
      return cacheResult == null || cacheResult.isSuccess();
    }

    @Nullable
    public CacheResult getCacheResult() {
      return cacheResult;
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
      return Objects.equal(this.cacheResult, that.cacheResult);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(getOperation(), cacheResult);
    }
  }

}
