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

import com.facebook.buck.event.BuckEvent;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/**
 * Base class for events about build rules.
 */
public abstract class BuildRuleEvent extends BuckEvent {
  private final BuildRule rule;

  protected BuildRuleEvent(BuildRule rule) {
    this.rule = rule;
  }

  public BuildRule getBuildRule() {
    return rule;
  }

  @Override
  public String getValueString() {
    return rule.getFullyQualifiedName();
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof BuildRuleEvent)) {
      return false;
    }

    return Objects.equal(getClass(), o.getClass()) &&
        Objects.equal(getBuildRule(), ((BuildRuleEvent) o).getBuildRule());
  }

  @Override
  public int hashCode() {
    return rule.hashCode();
  }

  public static Started started(BuildRule rule) {
    return new Started(rule);
  }

  public static Finished finished(BuildRule rule, BuildRuleStatus status, CacheResult cacheResult) {
    return new Finished(rule, status, cacheResult);
  }

  public static class Started extends BuildRuleEvent {
    protected Started(BuildRule rule) {
      super(rule);
    }

    @Override
    protected String getEventName() {
      return "BuildRuleStarted";
    }
  }

  public static class Finished extends BuildRuleEvent {
    private final BuildRuleStatus status;
    private final CacheResult cacheResult;

    protected Finished(BuildRule rule, BuildRuleStatus status, CacheResult cacheResult) {
      super(rule);
      this.status = Preconditions.checkNotNull(status);
      this.cacheResult = Preconditions.checkNotNull(cacheResult);
    }

    public BuildRuleStatus getStatus() {
      return status;
    }

    public CacheResult getCacheResult() {
      return cacheResult;
    }

    @Override
    public String toString() {
      return String.format("BuildRuleFinished(%s): %s %s",
          getBuildRule(),
          getStatus(),
          getCacheResult());
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }

      Finished that = (Finished)o;
      return Objects.equal(getStatus(), that.getStatus()) &&
          Objects.equal(getCacheResult(), that.getCacheResult());
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(getBuildRule(),
          getStatus(),
          getCacheResult());
    }

    @Override
    protected String getEventName() {
      return "BuildRuleFinished";
    }
  }

}
