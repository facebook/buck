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
import com.facebook.buck.util.MoreCollectors;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Event produced for HttpArtifactCache operations containing different stats.
 */
public abstract class HttpArtifactCacheEvent extends ArtifactCacheEvent {

  public static final ArtifactCacheEvent.CacheMode CACHE_MODE =
      ArtifactCacheEvent.CacheMode.http;

  protected HttpArtifactCacheEvent(
      EventKey eventKey,
      ArtifactCacheEvent.Operation operation,
      Optional<String> target,
      ImmutableSet<RuleKey> ruleKeys,
      ArtifactCacheEvent.InvocationType invocationType) {
    super(
        eventKey,
        CACHE_MODE,
        operation,
        target,
        ruleKeys,
        invocationType);
  }

  public static Started newFetchStartedEvent(ImmutableSet<RuleKey> ruleKeys) {
    return new Started(ArtifactCacheEvent.Operation.FETCH, ruleKeys);
  }

  public static Started newStoreStartedEvent(Scheduled scheduled) {
    return new Started(scheduled);
  }

  public static Scheduled newStoreScheduledEvent(
      Optional<String> target,
      ImmutableSet<RuleKey> ruleKeys) {
    return new Scheduled(ArtifactCacheEvent.Operation.STORE, target, ruleKeys);
  }

  public static Shutdown newShutdownEvent() {
    return new Shutdown();
  }

  public static Finished.Builder newFinishedEventBuilder(Started event) {
    return new Finished.Builder(event);
  }

  public static class Scheduled extends HttpArtifactCacheEvent {

    public Scheduled(ArtifactCacheEvent.Operation operation,
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

    private static final String ARTIFACT_SIZE_BYTES_KEY = "artifact_size_bytes";
    @JsonProperty("event_info")
    private final ImmutableMap<String, Object> eventInfo;

    @JsonIgnore
    private final Started startedEvent;

    @JsonProperty("request_duration_millis")
    private long requestDurationMillis;

    @JsonIgnore
    private boolean wasUploadSuccessful;

    private Finished(Started event,
        ImmutableMap<String, Object> data,
        boolean wasUploadSuccessful,
        Optional<CacheResult> cacheResult) {
      super(
          event.getEventKey(),
          CACHE_MODE,
          event.getOperation(),
          event.getTarget(),
          event.getRuleKeys(),
          event.getInvocationType(),
          cacheResult);
      this.startedEvent = event;
      this.eventInfo = data;
      this.requestDurationMillis = -1;
      this.wasUploadSuccessful = wasUploadSuccessful;
    }

    public Optional<Long> getArtifactSizeBytes() {
      if (eventInfo.containsKey(ARTIFACT_SIZE_BYTES_KEY)) {
        return Optional.of((long) eventInfo.get(ARTIFACT_SIZE_BYTES_KEY));
      }

      return Optional.empty();
    }

    public long getRequestDurationMillis() {
      return requestDurationMillis;
    }

    public ImmutableMap<String, Object> getEventInfo() {
      return eventInfo;
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

    public boolean wasUploadSuccessful() {
      return wasUploadSuccessful;
    }

    public static class Builder {
      private final Map<String, Object> data;
      private boolean wasUploadSuccessful;
      private Optional<CacheResult> cacheResult = Optional.empty();

      private final Started startedEvent;

      private Builder(Started event) {
        this.data = Maps.newHashMap();
        this.startedEvent = event;
      }

      public HttpArtifactCacheEvent.Finished build() {
        return new HttpArtifactCacheEvent.Finished(
            startedEvent, ImmutableMap.copyOf(data), wasUploadSuccessful, cacheResult);
      }

      public Builder setArtifactSizeBytes(long artifactSizeBytes) {
        data.put(ARTIFACT_SIZE_BYTES_KEY, artifactSizeBytes);
        return this;
      }

      public Builder setArtifactContentHash(String contentHash) {
        data.put("artifact_content_hash", contentHash);
        return this;
      }

      public Builder setRuleKeys(Iterable<RuleKey> ruleKeys) {
        // Make sure we expand any lazy evaluation Iterators so Json serialization works correctly.
        List<String> keysAsStrings = StreamSupport.stream(ruleKeys.spliterator(), false)
            .map(Object::toString)
            .collect(MoreCollectors.toImmutableList());
        data.put("rule_keys", keysAsStrings);
        return this;
      }

      public Builder setErrorMessage(String errorMessage) {
        data.put("error_msg", errorMessage);
        return this;
      }

      public Builder setWasUploadSuccessful(boolean wasUploadSuccessful) {
        data.put("was_upload_successful", wasUploadSuccessful);
        this.wasUploadSuccessful = wasUploadSuccessful;
        return this;
      }

      public Builder setRequestSizeBytes(long sizeBytes) {
        data.put("request_size_bytes", sizeBytes);
        return this;
      }

      public Builder setFetchResult(CacheResult cacheResult) {
        data.put("fetch_result", cacheResult.toString());
        this.cacheResult = Optional.of(cacheResult);
        return this;
      }

      public Builder setResponseSizeBytes(long sizeBytes) {
        data.put("response_size_bytes", sizeBytes);
        return this;
      }
    }
  }
}
