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

import com.facebook.buck.event.EventKey;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.rules.RuleKey;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;

/**
 * Event produced for HttpArtifactCache operations containing different stats.
 */
public abstract class HttpArtifactCacheEvent extends ArtifactCacheEvent {

  public static final ArtifactCacheEvent.CacheMode CACHE_MODE =
      ArtifactCacheEvent.CacheMode.http;

  protected HttpArtifactCacheEvent(
      EventKey eventKey,
      ArtifactCacheEvent.Operation operation,
      ImmutableSet<RuleKey> ruleKeys) {
    super(
        eventKey,
        CACHE_MODE,
        operation,
        ruleKeys);
  }

  public static Started newFetchStartedEvent(ImmutableSet<RuleKey> ruleKeys) {
    return new Started(ArtifactCacheEvent.Operation.FETCH, ruleKeys);
  }

  public static Started newStoreStartedEvent(ImmutableSet<RuleKey> ruleKeys) {
    return new Started(ArtifactCacheEvent.Operation.STORE, ruleKeys);
  }

  public static Finished.Builder newFinishedEventBuilder(Started event) {
    return new Finished.Builder(event);
  }

  public static class Started extends ArtifactCacheEvent.AbstractStarted {
    public Started(ArtifactCacheEvent.Operation operation,
        ImmutableSet<RuleKey> ruleKeys) {
      super(
          EventKey.unique(),
          CACHE_MODE,
          operation,
          ruleKeys);
    }

    @Override
    public String getEventName() {
      return "HttpArtifactCacheEvent.Started";
    }
  }

  public static class Finished extends ArtifactCacheEvent.AbstractFinished {

    @JsonProperty("event_info")
    private final ImmutableMap<String, Object> eventInfo;

    @JsonIgnore
    private final HttpArtifactCacheEvent.Started startedEvent;

    @JsonProperty("request_duration_millis")
    private long requestDurationMillis;

    private Finished(HttpArtifactCacheEvent.Started event,
        ImmutableMap<String, Object> data,
        Optional<CacheResult> cacheResult) {
      super(
          event.getEventKey(),
          CACHE_MODE,
          event.getOperation(),
          event.getRuleKeys(),
          cacheResult);
      this.startedEvent = event;
      this.eventInfo = data;
      this.requestDurationMillis = -1;
    }

    public long getRequestDurationMillis() {
      return requestDurationMillis;
    }

    public ImmutableMap<String, Object> getEventInfo() {
      return eventInfo;
    }

    @Override
    public void configure(
        long timestamp, long nanoTime, long threadId, BuildId buildId) {
      super.configure(timestamp, nanoTime, threadId, buildId);
      requestDurationMillis = timestamp - startedEvent.getTimestamp();
    }

    @Override
    public String getEventName() {
      return "HttpArtifactCacheEvent.Finished";
    }

    public static class Builder {
      private final Map<String, Object> data;
      private Optional<CacheResult> cacheResult = Optional.absent();

      private final HttpArtifactCacheEvent.Started startedEvent;

      private Builder(HttpArtifactCacheEvent.Started event) {
        this.data = Maps.newHashMap();
        this.startedEvent = event;
      }

      public HttpArtifactCacheEvent.Finished build() {
        return new HttpArtifactCacheEvent.Finished(
            startedEvent, ImmutableMap.copyOf(data), cacheResult);
      }

      public Builder setArtifactSizeBytes(long artifactSizeBytes) {
        data.put("artifact_size_bytes", artifactSizeBytes);
        return this;
      }

      public Builder setArtifactContentHash(String contentHash) {
        data.put("artifact_content_hash", contentHash);
        return this;
      }

      public Builder setRuleKeys(Iterable<RuleKey> ruleKeys) {
        // Make sure we expand any lazy evaluation Iterators so Json serialization works correctly.
        List<String> keysAsStrings = FluentIterable.from(ruleKeys)
            .transform(Functions.toStringFunction())
            .toList();
        data.put("rule_keys", keysAsStrings);
        return this;
      }

      public Builder setErrorMessage(String errorMessage) {
        data.put("error_msg", errorMessage);
        return this;
      }

      public Builder setWasUploadSuccessful(boolean wasUploadSuccessful) {
        data.put("was_upload_successful", wasUploadSuccessful);
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
