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
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

/**
 * Base class for events about build rules.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class ArtifactCacheEvent extends AbstractBuckEvent implements LeafEvent {
  public static enum Operation {
    FETCH,
    STORE,
    COMPRESS,
    DECOMPRESS,
  }

  private final Operation operation;
  private final RuleKey ruleKey;

  protected ArtifactCacheEvent(Operation operation, RuleKey ruleKey) {
    this.operation = operation;
    this.ruleKey = ruleKey;
  }

  @Override
  public String getCategory() {
    return "artifact_" + operation.toString().toLowerCase();
  }

  @Override
  public String getValueString() {
    return String.format("%s:%s", operation.toString().toLowerCase(), ruleKey.toString());
  }

  public RuleKey getRuleKey() {
    return ruleKey;
  }

  @Override
  public boolean isRelatedTo(BuckEvent event) {
    if (!(event instanceof ArtifactCacheEvent)) {
      return false;
    }

    ArtifactCacheEvent that = (ArtifactCacheEvent) event;

    return Objects.equal(getOperation(), that.getOperation()) &&
        Objects.equal(getRuleKey(), that.getRuleKey());
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(getOperation(), getRuleKey(), getThreadId());
  }

  public Operation getOperation() {
    return operation;
  }

  public static Started started(Operation operation, RuleKey ruleKey) {
    return new Started(operation, ruleKey);
  }

  public static Finished finished(Operation operation, RuleKey ruleKey) {
    return new Finished(operation, ruleKey, Optional.<CacheResult>absent());
  }

  public static Finished finished(Operation operation,
      RuleKey ruleKey,
      CacheResult cacheResult) {
    return new Finished(operation, ruleKey, Optional.of(cacheResult));
  }

  public static class Started extends ArtifactCacheEvent {
    protected Started(Operation operation, RuleKey ruleKey) {
      super(operation, ruleKey);
    }

    @Override
    public String getEventName() {
      return String.format("Artifact%sCacheStarted",
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, getOperation().toString()));
    }
  }

  public static class Finished extends ArtifactCacheEvent {
    /** Not present iff {@link #getOperation()} is not {@link Operation#FETCH}. */
    private final Optional<CacheResult> cacheResult;

    public Optional<CacheResult> getCacheResult() {
      return cacheResult;
    }

    protected Finished(Operation operation, RuleKey ruleKey, Optional<CacheResult> cacheResult) {
      super(operation, ruleKey);
      Preconditions.checkArgument(
          (operation.equals(Operation.FETCH) && cacheResult.isPresent()) ||
          (!operation.equals(Operation.FETCH) && !cacheResult.isPresent()),
          "For FETCH operations, cacheResult must be non-null. " +
          "For non-FETCH operations, cacheResult must be null.");
      this.cacheResult = cacheResult;
    }

    public boolean isSuccess() {
      return !cacheResult.isPresent() || cacheResult.get().isSuccess();
    }

    @Override
    public String getEventName() {
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
      return Objects.hashCode(getOperation(), getRuleKey(), getThreadId(), cacheResult);
    }
  }

}
