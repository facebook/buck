/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.rules.RuleKey;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.CaseFormat;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

public abstract class ArtifactCacheEvent extends AbstractBuckEvent implements LeafEvent {
  public enum Operation {
    FETCH,
    STORE,
    COMPRESS,
    DECOMPRESS,
  }

  public enum CacheMode {
    aggregate, dir, http
  }

  @JsonIgnore
  private final CacheMode cacheMode;

  @JsonProperty("operation")
  private final Operation operation;

  @JsonIgnore
  private final ImmutableSet<RuleKey> ruleKeys;

  protected ArtifactCacheEvent(
      EventKey eventKey,
      CacheMode cacheMode,
      Operation operation,
      ImmutableSet<RuleKey> ruleKeys) {
    super(eventKey);
    this.cacheMode = cacheMode;
    this.operation = operation;
    this.ruleKeys = ruleKeys;
  }

  @Override
  protected String getValueString() {
    return getEventName() + getEventKey().toString();
  }

  @Override
  public String getCategory() {
    if (cacheMode == CacheMode.aggregate) {
      return "artifact_" + operation.toString().toLowerCase();
    } else {
      return cacheMode.toString().toLowerCase() +
          "_artifact_" + operation.toString().toLowerCase();
    }
  }

  public Operation getOperation() {
    return operation;
  }

  public ImmutableSet<RuleKey> getRuleKeys() {
    return ruleKeys;
  }

  public static class AggregateArtifactCacheEventFactory implements ArtifactCacheEventFactory {
    @Override
    public AbstractStarted newFetchStartedEvent(ImmutableSet<RuleKey> ruleKeys) {
      return started(Operation.FETCH, ruleKeys);
    }

    @Override
    public AbstractStarted newStoreStartedEvent(ImmutableSet<RuleKey> ruleKeys) {
      return started(Operation.STORE, ruleKeys);
    }

    @Override
    public AbstractFinished newStoreFinishedEvent(AbstractStarted started) {
      return finished(started);
    }

    @Override
    public AbstractFinished newFetchFinishedEvent(
        AbstractStarted started, CacheResult cacheResult) {
      return finished(started, cacheResult);
    }
  }

  public static Started started(Operation operation, ImmutableSet<RuleKey> ruleKeys) {
    return new Started(EventKey.unique(), CacheMode.aggregate, operation, ruleKeys);
  }

  public static Finished finished(AbstractStarted started) {
    return new Finished(
        started.getEventKey(),
        CacheMode.aggregate,
        started.getOperation(),
        started.getRuleKeys(),
        Optional.<CacheResult>absent());
  }

  public static Finished finished(AbstractStarted started, CacheResult cacheResult) {
    return new Finished(
        started.getEventKey(),
        CacheMode.aggregate,
        started.getOperation(),
        started.getRuleKeys(),
        Optional.of(cacheResult));
  }

  public static class Started extends AbstractStarted {
    protected Started(
        EventKey eventKey,
        CacheMode cacheMode,
        Operation operation,
        ImmutableSet<RuleKey> ruleKeys) {
      super(eventKey, cacheMode, operation, ruleKeys);
    }
  }

  public static class Finished extends AbstractFinished {
    protected Finished(
        EventKey eventKey,
        CacheMode cacheMode,
        Operation operation,
        ImmutableSet<RuleKey> ruleKeys,
        Optional<CacheResult> cacheResult) {
      super(eventKey, cacheMode, operation, ruleKeys, cacheResult);
    }
  }

  public abstract static class AbstractStarted extends ArtifactCacheEvent {
    protected AbstractStarted(
        EventKey eventKey,
        CacheMode cacheMode,
        Operation operation,
        ImmutableSet<RuleKey> ruleKeys) {
      super(eventKey, cacheMode, operation, ruleKeys);
    }

    @Override
    public String getEventName() {
      return String.format("Artifact%sCacheStarted",
          CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, getOperation().toString()));
    }
  }

  public abstract static class AbstractFinished extends ArtifactCacheEvent {
    /** Not present iff {@link #getOperation()} is not {@link Operation#FETCH}. */
    private final Optional<CacheResult> cacheResult;

    protected AbstractFinished(
        EventKey eventKey,
        CacheMode cacheMode,
        Operation operation,
        ImmutableSet<RuleKey> ruleKeys,
        Optional<CacheResult> cacheResult) {
      super(eventKey, cacheMode, operation, ruleKeys);
      Preconditions.checkArgument(
          (!operation.equals(Operation.FETCH) || cacheResult.isPresent()),
          "For FETCH operations, cacheResult must be non-null. " +
              "For non-FETCH operations, cacheResult must be null.");
      this.cacheResult = cacheResult;
    }

    public Optional<CacheResult> getCacheResult() {
      return cacheResult;
    }

    public boolean isSuccess() {
      return !cacheResult.isPresent() || cacheResult.get().getType().isSuccess();
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
      // Because super.equals compares the EventKey, getting here means that we've somehow managed
      // to create 2 Finished events for the same Started event.
      throw new UnsupportedOperationException("Multiple conflicting Finished events detected.");
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(super.hashCode(), cacheResult);
    }
  }
}
