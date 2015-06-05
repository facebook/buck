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
import com.google.common.hash.HashCode;

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
  public boolean isRelatedTo(BuckEvent event) {
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
    ruleKey = rule.getRuleKey().toString();
    return ruleKey;
  }

  public static Started started(BuildRule rule) {
    return new Started(rule);
  }

  public static Finished finished(BuildRule rule,
      BuildRuleStatus status,
      CacheResult cacheResult,
      Optional<BuildRuleSuccessType> successType,
      Optional<HashCode> outputHash,
      Optional<Long> outputSize) {
    return new Finished(rule, status, cacheResult, successType, outputHash, outputSize);
  }

  public static Suspended suspended(BuildRule rule) {
    return new Suspended(rule);
  }

  public static Resumed resumed(BuildRule rule) {
    return new Resumed(rule);
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
    private final Optional<BuildRuleSuccessType> successType;
    private final Optional<HashCode> outputHash;
    private final Optional<Long> outputSize;

    protected Finished(BuildRule rule,
        BuildRuleStatus status,
        CacheResult cacheResult,
        Optional<BuildRuleSuccessType> successType,
        Optional<HashCode> outputHash,
        Optional<Long> outputSize) {
      super(rule);
      this.status = status;
      this.cacheResult = cacheResult;
      this.successType = successType;
      this.outputHash = outputHash;
      this.outputSize = outputSize;
    }

    public BuildRuleStatus getStatus() {
      return status;
    }

    public CacheResult getCacheResult() {
      return cacheResult;
    }

    @JsonIgnore
    public Optional<BuildRuleSuccessType> getSuccessType() {
      return successType;
    }

    @JsonIgnore
    public Optional<HashCode> getOutputHash() {
      return outputHash;
    }

    @JsonIgnore
    public Optional<Long> getOutputSize() {
      return outputSize;
    }

    @Override
    public String toString() {
      RuleKey ruleKey;
      ruleKey = getBuildRule().getRuleKey();

      String success = successType.isPresent() ? successType.get().toString() : "MISSING";
      return String.format("BuildRuleFinished(%s): %s %s %s %s",
          getBuildRule(),
          getStatus(),
          getCacheResult(),
          success,
          ruleKey);
    }

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) {
        return false;
      }

      Finished that = (Finished) o;
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

  public static class Suspended extends BuildRuleEvent {

    protected Suspended(BuildRule rule) {
      super(rule);
    }

    @Override
    public String getEventName() {
      return "BuildRuleSuspended";
    }

  }

  public static class Resumed extends BuildRuleEvent {

    protected Resumed(BuildRule rule) {
      super(rule);
    }

    @Override
    public String getEventName() {
      return "BuildRuleResumed";
    }

  }

}
