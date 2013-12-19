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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.io.IOException;

/**
 * Base class for events about build rules.
 */
@SuppressWarnings("PMD.OverrideBothEqualsAndHashcode")
public abstract class BuildRuleEvent extends AbstractBuckEvent {
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
  public boolean eventsArePair(BuckEvent event) {
    if (!(event instanceof BuildRuleEvent)) {
      return false;
    }

    return Objects.equal(getBuildRule(), ((BuildRuleEvent) event).getBuildRule());
  }

  @Override
  public int hashCode() {
    return rule.hashCode();
  }

  /**
   * @return The string representation of the rulekey or the error text.
   */
  public String getRuleKeySafe() {
    String ruleKey;
    try {
      ruleKey = rule.getRuleKey().toString();
    } catch (IOException e) {
      ruleKey = "INACCESSIBLE: " + e.getMessage();
    }
    return ruleKey;
  }

  public static Started started(BuildRule rule) {
    return new Started(rule);
  }

  public static Finished finished(BuildRule rule,
      BuildRuleStatus status,
      CacheResult cacheResult,
      Optional<BuildRuleSuccess.Type> successType) {
    return new Finished(rule, status, cacheResult, successType);
  }

  public static class Started extends BuildRuleEvent {
    protected Started(BuildRule rule) {
      super(rule);
    }

    @Override
    public String getEventName() {
      return "BuildRuleStarted";
    }
  }

  public static class Finished extends BuildRuleEvent {
    private final BuildRuleStatus status;
    private final CacheResult cacheResult;
    private final Optional<BuildRuleSuccess.Type> successType;

    protected Finished(BuildRule rule,
        BuildRuleStatus status,
        CacheResult cacheResult,
        Optional<BuildRuleSuccess.Type> successType) {
      super(rule);
      this.status = Preconditions.checkNotNull(status);
      this.cacheResult = Preconditions.checkNotNull(cacheResult);
      this.successType = Preconditions.checkNotNull(successType);
    }

    public BuildRuleStatus getStatus() {
      return status;
    }

    public CacheResult getCacheResult() {
      return cacheResult;
    }

    @JsonIgnore
    public Optional<BuildRuleSuccess.Type> getSuccessType() {
      return successType;
    }

    @Override
    public String toString() {
      RuleKey ruleKey;
      try {
        ruleKey = getBuildRule().getRuleKey();
      } catch (IOException e) {
        throw new RuntimeException("RuleKey should already be computed if this is built.", e);
      }

      return String.format("BuildRuleFinished(%s): %s %s %s",
          getBuildRule(),
          getStatus(),
          getCacheResult(),
          ruleKey);
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }

      Finished that = (Finished)o;
      return Objects.equal(getStatus(), that.getStatus()) &&
          Objects.equal(getCacheResult(), that.getCacheResult()) &&
          Objects.equal(getSuccessType(), that.getSuccessType());
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(getBuildRule(),
          getStatus(),
          getCacheResult(),
          getSuccessType());
    }

    @Override
    public String getEventName() {
      return "BuildRuleFinished";
    }
  }

}
