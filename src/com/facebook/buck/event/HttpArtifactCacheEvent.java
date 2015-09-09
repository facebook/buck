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

package com.facebook.buck.event;

import com.facebook.buck.model.BuildId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;

/**
 * Event produced for HttpArtifactCache operations containing different stats.
 */
public abstract class HttpArtifactCacheEvent extends AbstractBuckEvent {

  /**
   * Operation executed by the HttpArtifactCache.
   */
  public enum Operation {
    FETCH,
    STORE
  }

  @JsonProperty("operation")
  private final Operation operation;

  protected HttpArtifactCacheEvent(EventKey eventKey, Operation operation) {
    super(eventKey);
    this.operation = operation;
  }

  public static Started newFetchStartedEvent() {
    return new Started(Operation.FETCH);
  }

  public static Started newStoreStartedEvent() {
    return new Started(Operation.STORE);
  }

  public static Finished.Builder newFinishedEventBuilder(Started event) {
    return new Finished.Builder(event);
  }

  public Operation getOperation() {
    return operation;
  }

  @Override
  protected String getValueString() {
    return getEventName() + getEventKey().toString();
  }

  public static class Started extends HttpArtifactCacheEvent {

    public Started(Operation operation) {
      super(EventKey.unique(), operation);
    }

    @Override
    public String getEventName() {
      return "HttpArtifactCacheEvent.Started";
    }
  }

  public static class Finished extends HttpArtifactCacheEvent {

    @JsonProperty("event_info")
    private final ImmutableMap<String, Object> eventInfo;

    @JsonIgnore
    private final Started startedEvent;

    @JsonProperty("request_duration_millis")
    private long requestDurationMillis;

    private Finished(Started event, ImmutableMap<String, Object> data) {
      super(event.getEventKey(), event.getOperation());
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

      private final Started startedEvent;

      private Builder(Started event) {
        this.data = Maps.newHashMap();
        this.startedEvent = event;
      }

      public Finished build() {
        return new Finished(startedEvent, ImmutableMap.copyOf(data));
      }

      public Builder setArtifactSizeBytes(long artifactSizeBytes) {
        data.put("artifact_size_bytes", artifactSizeBytes);
        return this;
      }

      public Builder setArtifactContentHash(String contentHash) {
        data.put("artifact_content_hash", contentHash);
        return this;
      }

      public Builder setRuleKeys(Iterable<?> ruleKeys) {
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

      public Builder setFetchResult(String cacheResult) {
        data.put("fetch_result", cacheResult);
        return this;
      }

      public Builder setResponseSizeBytes(long sizeBytes) {
        data.put("response_size_bytes", sizeBytes);
        return this;
      }
    }
  }
}
