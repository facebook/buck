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
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.Optional;
import org.immutables.value.Value;

/** Event produced for HttpArtifactCache operations containing different stats. */
public abstract class HttpArtifactCacheEvent extends ArtifactCacheEvent {

  public static final ArtifactCacheEvent.CacheMode CACHE_MODE = ArtifactCacheEvent.CacheMode.http;

  protected HttpArtifactCacheEvent(
      EventKey eventKey,
      ArtifactCacheEvent.Operation operation,
      Optional<String> target,
      ImmutableSet<RuleKey> ruleKeys,
      ArtifactCacheEvent.InvocationType invocationType) {
    super(eventKey, CACHE_MODE, operation, target, ruleKeys, invocationType);
  }

  public static Started newFetchStartedEvent(RuleKey ruleKey) {
    return new Started(ArtifactCacheEvent.Operation.FETCH, ImmutableSet.of(ruleKey));
  }

  public static Started newStoreStartedEvent(Scheduled scheduled) {
    return new Started(scheduled);
  }

  public static Scheduled newStoreScheduledEvent(
      Optional<String> target, ImmutableSet<RuleKey> ruleKeys) {
    return new Scheduled(ArtifactCacheEvent.Operation.STORE, target, ruleKeys);
  }

  public static Shutdown newShutdownEvent() {
    return new Shutdown();
  }

  public static Finished.Builder newFinishedEventBuilder(Started event) {
    return new Finished.Builder(event);
  }

  public static HttpArtifactCacheEvent.MultiFetchStarted newMultiFetchStartedEvent(
      ImmutableList<RuleKey> ruleKeys) {
    return new MultiFetchStarted(ImmutableSet.copyOf(ruleKeys));
  }

  public static class Scheduled extends HttpArtifactCacheEvent {

    public Scheduled(
        ArtifactCacheEvent.Operation operation,
        Optional<String> target,
        ImmutableSet<RuleKey> ruleKeys) {
      super(
          EventKey.unique(),
          operation,
          target,
          ruleKeys,
          ArtifactCacheEvent.InvocationType.ASYNCHRONOUS);
    }

    @Override
    public String getEventName() {
      return "HttpArtifactCacheEvent.Scheduled";
    }
  }

  public static class Started extends ArtifactCacheEvent.Started {
    public Started(Scheduled event) {
      super(
          event.getEventKey(),
          CACHE_MODE,
          event.getOperation(),
          event.getTarget(),
          event.getRuleKeys(),
          event.getInvocationType());
    }

    public Started(ArtifactCacheEvent.Operation operation, ImmutableSet<RuleKey> ruleKeys) {
      super(
          EventKey.unique(),
          CACHE_MODE,
          operation,
          Optional.empty(),
          ruleKeys,
          ArtifactCacheEvent.InvocationType.SYNCHRONOUS);
    }

    @Override
    public String getEventName() {
      return "HttpArtifactCacheEvent.Started";
    }
  }

  public static class Shutdown extends AbstractBuckEvent {
    public Shutdown() {
      super(EventKey.unique());
    }

    @Override
    public String getEventName() {
      return "HttpArtifactCacheEvent.Shutdown";
    }

    @Override
    protected String getValueString() {
      return getEventName() + getEventKey().toString();
    }
  }

  public static class Finished extends ArtifactCacheEvent.Finished {

    @JsonIgnore private final Started startedEvent;

    @JsonIgnore private final Optional<HttpArtifactCacheEventFetchData> fetchData;

    @JsonIgnore private final Optional<HttpArtifactCacheEventStoreData> storeData;

    @JsonProperty("request_duration_millis")
    private long requestDurationMillis;

    public Finished(Started event, Optional<String> target, HttpArtifactCacheEventFetchData data) {
      super(
          event.getEventKey(),
          CACHE_MODE,
          event.getOperation(),
          Preconditions.checkNotNull(target),
          event.getRuleKeys(),
          event.getInvocationType(),
          data.getFetchResult());
      this.startedEvent = event;
      this.requestDurationMillis = -1;
      this.fetchData = Optional.of(data);
      this.storeData = Optional.empty();
    }

    public Finished(Started event, HttpArtifactCacheEventStoreData data) {
      super(
          event.getEventKey(),
          CACHE_MODE,
          event.getOperation(),
          event.getTarget(),
          event.getRuleKeys(),
          event.getInvocationType(),
          Optional.empty());
      this.startedEvent = event;
      this.requestDurationMillis = -1;
      this.fetchData = Optional.empty();
      this.storeData = Optional.of(data);
    }

    public long getRequestDurationMillis() {
      return requestDurationMillis;
    }

    public HttpArtifactCacheEventFetchData getFetchData() {
      Preconditions.checkState(fetchData.isPresent());
      return fetchData.get();
    }

    public HttpArtifactCacheEventStoreData getStoreData() {
      Preconditions.checkState(storeData.isPresent());
      return storeData.get();
    }

    @Override
    public void configure(
        long timestampMillis,
        long nanoTime,
        long userThreadNanoTime,
        long threadId,
        BuildId buildId) {
      super.configure(timestampMillis, nanoTime, userThreadNanoTime, threadId, buildId);
      requestDurationMillis = timestampMillis - startedEvent.getTimestamp();
    }

    @Override
    public String getEventName() {
      return "HttpArtifactCacheEvent.Finished";
    }

    public static class Builder {
      private final Started startedEvent;
      private HttpArtifactCacheEventFetchData.Builder fetchDataBuilder;
      private HttpArtifactCacheEventStoreData.Builder storeDataBuilder;
      private Optional<String> target;

      private Builder(Started event) {
        this.startedEvent = event;
        this.storeDataBuilder = HttpArtifactCacheEventStoreData.builder();
        this.fetchDataBuilder = HttpArtifactCacheEventFetchData.builder();
        this.target = Optional.empty();
      }

      public HttpArtifactCacheEvent.Finished build() {
        if (startedEvent.getOperation() == Operation.FETCH) {
          RuleKey requestsRuleKey =
              Preconditions.checkNotNull(Iterables.getFirst(startedEvent.getRuleKeys(), null));
          fetchDataBuilder.setRequestedRuleKey(requestsRuleKey);
          return new HttpArtifactCacheEvent.Finished(
              startedEvent, target, fetchDataBuilder.build());
        } else {
          storeDataBuilder.setRuleKeys(startedEvent.getRuleKeys());
          return new HttpArtifactCacheEvent.Finished(startedEvent, storeDataBuilder.build());
        }
      }

      public HttpArtifactCacheEventFetchData.Builder getFetchBuilder() {
        return fetchDataBuilder;
      }

      public HttpArtifactCacheEventStoreData.Builder getStoreBuilder() {
        return storeDataBuilder;
      }

      public Builder setFetchDataBuilder(HttpArtifactCacheEventFetchData.Builder fetchDataBuilder) {
        this.fetchDataBuilder = fetchDataBuilder;
        return this;
      }

      public Builder setTarget(Optional<String> target) {
        this.target = target;
        return this;
      }
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractHttpArtifactCacheEventFetchData {
    Optional<Long> getResponseSizeBytes();

    Optional<CacheResult> getFetchResult();

    RuleKey getRequestedRuleKey();

    Optional<String> getArtifactContentHash();

    Optional<Long> getArtifactSizeBytes();

    Optional<String> getErrorMessage();

    ImmutableSet<RuleKey> getAssociatedRuleKeys();
  }

  @Value.Immutable
  @BuckStyleImmutable
  interface AbstractHttpArtifactCacheEventStoreData {
    Optional<Long> getRequestSizeBytes();

    Optional<Boolean> wasStoreSuccessful();

    ImmutableSet<RuleKey> getRuleKeys();

    Optional<String> getArtifactContentHash();

    Optional<Long> getArtifactSizeBytes();

    Optional<String> getErrorMessage();

    /** @return if the store was for an actual artifact of for a manifest. */
    Optional<Boolean> wasStoreForManifest();
  }

  static class MultiFetchStarted extends Started {
    public MultiFetchStarted(ImmutableSet<RuleKey> ruleKeys) {
      super(Operation.MULTI_FETCH, ruleKeys);
    }
  }
}
