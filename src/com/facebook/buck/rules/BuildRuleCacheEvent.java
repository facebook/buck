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
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.LeafEvent;
import com.facebook.buck.event.WorkAdvanceEvent;

/** Base class for events about build rules. */
public abstract class BuildRuleCacheEvent extends AbstractBuckEvent
    implements WorkAdvanceEvent, LeafEvent {
  private final BuildRule rule;
  protected CacheStepType cacheType;

  public enum CacheStepType {
    INPUT_BASED("input_based"),
    DEPFILE_BASED("depfile_based"),
    ABI_BASED("abi_based");

    private final String name;

    CacheStepType(String readableName) {
      this.name = readableName;
    }

    public String getName() {
      return name;
    }
  }

  protected BuildRuleCacheEvent(EventKey key, BuildRule rule, CacheStepType cacheType) {
    super(key);
    this.rule = rule;
    this.cacheType = cacheType;
  }

  public static Scope startCacheCheckScope(
      BuckEventBus eventBus, BuildRule rule, CacheStepType cacheType) {
    CacheStepStarted started = new CacheStepStarted(rule, cacheType);
    eventBus.post(started);
    return () -> eventBus.post(finished(started));
  }

  public static CacheStepStarted started(BuildRule rule, CacheStepType type) {
    return new CacheStepStarted(rule, type);
  }

  public static CacheStepFinished finished(CacheStepStarted started) {
    return new CacheStepFinished(
        started.getEventKey(), started.getBuildRule(), started.getCacheType());
  }

  public BuildRule getBuildRule() {
    return rule;
  }

  @Override
  public String getValueString() {
    return rule.getFullyQualifiedName();
  }

  public CacheStepType getCacheType() {
    return cacheType;
  }

  @Override
  public String getCategory() {
    return "checking_cache_" + cacheType.getName();
  }

  public static class CacheStepStarted extends BuildRuleCacheEvent {
    protected CacheStepStarted(BuildRule rule, CacheStepType cacheType) {
      super(EventKey.unique(), rule, cacheType);
    }

    @Override
    public String getEventName() {
      return "CacheStepStarted";
    }
  }

  public static class CacheStepFinished extends BuildRuleCacheEvent {
    protected CacheStepFinished(EventKey eventKey, BuildRule rule, CacheStepType cacheType) {
      super(eventKey, rule, cacheType);
    }

    @Override
    public String getEventName() {
      return "CacheStepFinished";
    }
  }
}
