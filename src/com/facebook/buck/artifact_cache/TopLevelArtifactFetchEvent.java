/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.artifact_cache;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Event produced from engine cache manager's view for fetching artifact. This event is only
 * consumed by the {@link com.facebook.buck.event.listener.stats.cache.NetworkStatsTracker} and used
 * for showing artifact download status in the console.
 */
public abstract class TopLevelArtifactFetchEvent extends AbstractBuckEvent {

  public static final String STARTED = "ConsoleArtifactCacheFetchEvent.Started";
  public static final String FINISHED = "ConsoleArtifactCacheFetchEvent.Finished";

  @JsonIgnore private final Optional<BuildTarget> target;
  @JsonIgnore private final RuleKey ruleKey;

  protected TopLevelArtifactFetchEvent(
      EventKey eventKey, Optional<BuildTarget> target, RuleKey ruleKey) {
    super(eventKey);
    this.target = target;
    this.ruleKey = ruleKey;
  }

  @Override
  protected String getValueString() {
    return getEventName() + ":" + ruleKey.toString();
  }

  @Override
  public abstract String getEventName();

  public Optional<BuildTarget> getTarget() {
    return target;
  }

  public RuleKey getRuleKey() {
    return ruleKey;
  }

  public static Started newFetchStartedEvent(@Nullable BuildTarget target, RuleKey ruleKey) {
    return new Started(target != null ? Optional.of(target) : Optional.empty(), ruleKey);
  }

  public static Finished newFetchSuccessEvent(Started event, CacheResult cacheResult) {
    return new Finished(event, cacheResult, Optional.empty());
  }

  public static Finished newFetchFailureEvent(
      Started event, CacheResult cacheResult, String errorMessage) {
    return new Finished(event, cacheResult, Optional.of(errorMessage));
  }

  /** Started event for fetching a ruleKey from engine cache manager's view */
  public static class Started extends TopLevelArtifactFetchEvent {

    protected Started(Optional<BuildTarget> target, RuleKey ruleKey) {
      super(EventKey.unique(), target, ruleKey);
    }

    @Override
    public String getEventName() {
      return STARTED;
    }
  }

  /** Finished event for fetching a ruleKey from engine cache manager's view */
  public static class Finished extends TopLevelArtifactFetchEvent {

    @JsonIgnore private final CacheResult cacheResult;
    @JsonIgnore private final Optional<String> errorMessage;

    protected Finished(Started event, CacheResult cacheResult, Optional<String> errorMessage) {
      super(event.getEventKey(), event.getTarget(), event.getRuleKey());
      this.cacheResult = cacheResult;
      this.errorMessage = errorMessage;
    }

    @Override
    public String getEventName() {
      return FINISHED;
    }

    public CacheResult getCacheResult() {
      return cacheResult;
    }

    public Optional<String> getErrorMessage() {
      return errorMessage;
    }
  }
}
