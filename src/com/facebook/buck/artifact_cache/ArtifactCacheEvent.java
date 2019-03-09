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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;

public abstract class ArtifactCacheEvent extends AbstractBuckEvent implements LeafEvent {

  public enum Operation {
    FETCH,
    MULTI_FETCH,
    STORE,
    MULTI_CONTAINS,
  }

  public enum InvocationType {
    SYNCHRONOUS,
    ASYNCHRONOUS,
  }

  public enum CacheMode {
    dir,
    http,
    sqlite
  }

  /**
   * For {@link Operation} STORE there are different store types, storing the actual artifact or the
   * manifest of it. For the cases of FETCH use NOT_APPLICABLE.
   */
  public enum StoreType {
    ARTIFACT,
    MANIFEST,
    NOT_APPLICABLE;

    public static StoreType fromArtifactInfo(ArtifactInfo info) {
      return info.isManifest() ? StoreType.MANIFEST : StoreType.ARTIFACT;
    }
  }

  @JsonIgnore private final CacheMode cacheMode;

  @JsonProperty("operation")
  private final Operation operation;

  @JsonIgnore private final ArtifactCacheEvent.InvocationType invocationType;

  @JsonIgnore private final Optional<BuildTarget> target;

  @JsonIgnore private final ImmutableSet<RuleKey> ruleKeys;

  @JsonIgnore private final StoreType storeType;

  protected ArtifactCacheEvent(
      EventKey eventKey,
      CacheMode cacheMode,
      Operation operation,
      Optional<BuildTarget> target,
      ImmutableSet<RuleKey> ruleKeys,
      ArtifactCacheEvent.InvocationType invocationType,
      StoreType storeType) {
    super(eventKey);
    this.cacheMode = cacheMode;
    this.operation = operation;
    this.target = target;
    this.ruleKeys = ruleKeys;
    this.invocationType = invocationType;
    this.storeType = storeType;
  }

  @Override
  protected String getValueString() {
    return getEventName() + getEventKey();
  }

  @Override
  public String getCategory() {
    return cacheMode.toString().toLowerCase() + "_artifact_" + operation.toString().toLowerCase();
  }

  public Operation getOperation() {
    return operation;
  }

  public ImmutableSet<RuleKey> getRuleKeys() {
    return ruleKeys;
  }

  public Optional<BuildTarget> getTarget() {
    return target;
  }

  public ArtifactCacheEvent.InvocationType getInvocationType() {
    return invocationType;
  }

  public StoreType getStoreType() {
    return storeType;
  }

  @Override
  public abstract String getEventName();

  public abstract static class Started extends ArtifactCacheEvent {

    protected Started(
        EventKey eventKey,
        CacheMode cacheMode,
        Operation operation,
        Optional<BuildTarget> target,
        ImmutableSet<RuleKey> ruleKeys,
        ArtifactCacheEvent.InvocationType invocationType) {
      super(
          eventKey,
          cacheMode,
          operation,
          target,
          ruleKeys,
          invocationType,
          StoreType.NOT_APPLICABLE);
    }

    protected Started(
        EventKey eventKey,
        CacheMode cacheMode,
        Operation operation,
        Optional<BuildTarget> target,
        ImmutableSet<RuleKey> ruleKeys,
        ArtifactCacheEvent.InvocationType invocationType,
        StoreType storeType) {
      super(eventKey, cacheMode, operation, target, ruleKeys, invocationType, storeType);
    }
  }

  public abstract static class Finished extends ArtifactCacheEvent {
    /** Not present iff {@link #getOperation()} is not {@link Operation#FETCH}. */
    private final Optional<CacheResult> cacheResult;

    protected Finished(
        EventKey eventKey,
        CacheMode cacheMode,
        Operation operation,
        Optional<BuildTarget> target,
        ImmutableSet<RuleKey> ruleKeys,
        ArtifactCacheEvent.InvocationType invocationType,
        Optional<CacheResult> cacheResult) {
      super(
          eventKey,
          cacheMode,
          operation,
          target,
          ruleKeys,
          invocationType,
          StoreType.NOT_APPLICABLE);
      Preconditions.checkArgument(
          (!operation.equals(Operation.FETCH) || cacheResult.isPresent()),
          "For FETCH operations, cacheResult must be non-null. "
              + "For non-FETCH operations, cacheResult may be null.");
      this.cacheResult = cacheResult;
    }

    protected Finished(
        EventKey eventKey,
        CacheMode cacheMode,
        Operation operation,
        Optional<BuildTarget> target,
        ImmutableSet<RuleKey> ruleKeys,
        ArtifactCacheEvent.InvocationType invocationType,
        Optional<CacheResult> cacheResult,
        StoreType storeType) {
      super(eventKey, cacheMode, operation, target, ruleKeys, invocationType, storeType);
      Preconditions.checkArgument(
          (!operation.equals(Operation.FETCH) || cacheResult.isPresent()),
          String.format(
              "For FETCH operations, cacheResult must be non-null. For non-FETCH "
                  + "operations, cacheResult may be null. The violating operation was %s for %s.",
              operation.name(), storeType.name()));
      this.cacheResult = cacheResult;
    }

    public Optional<CacheResult> getCacheResult() {
      return cacheResult;
    }

    public boolean isSuccess() {
      return !cacheResult.isPresent() || cacheResult.get().getType().isSuccess();
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
