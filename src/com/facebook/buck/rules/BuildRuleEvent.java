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

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.EventKey;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Optional;
import com.google.common.hash.HashCode;

/**
 * Base class for events about build rules.
 */
public abstract class BuildRuleEvent extends AbstractBuckEvent {
  private final BuildRule rule;
  private final String ruleKey;

  protected BuildRuleEvent(BuildRule rule, RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    super(EventKey.slowValueKey("BuildRuleEvent", rule.getFullyQualifiedName()));
    this.rule = rule;
    this.ruleKey = ruleKeyBuilderFactory.newInstance(rule).build().toString();
  }

  public BuildRule getBuildRule() {
    return rule;
  }

  @Override
  public String getValueString() {
    return rule.getFullyQualifiedName();
  }

  /**
   * @return The string representation of the rulekey or the error text.
   */
  public String getRuleKeySafe() {
    return ruleKey;
  }

  public static Started started(BuildRule rule, RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    return new Started(rule, ruleKeyBuilderFactory);
  }

  public static Finished finished(
      BuildRule rule,
      RuleKeyBuilderFactory ruleKeyBuilderFactory,
      BuildRuleStatus status,
      CacheResult cacheResult,
      Optional<BuildRuleSuccessType> successType,
      Optional<HashCode> outputHash,
      Optional<Long> outputSize) {
    return new Finished(
        rule,
        ruleKeyBuilderFactory,
        status,
        cacheResult,
        successType,
        outputHash,
        outputSize);
  }

  public static Suspended suspended(BuildRule rule, RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    return new Suspended(rule, ruleKeyBuilderFactory);
  }

  public static Resumed resumed(BuildRule rule, RuleKeyBuilderFactory ruleKeyBuilderFactory) {
    return new Resumed(rule, ruleKeyBuilderFactory);
  }

  public static class Started extends BuildRuleEvent {
    protected Started(BuildRule rule, RuleKeyBuilderFactory ruleKeyBuilderFactory) {
      super(rule, ruleKeyBuilderFactory);
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

    protected Finished(
        BuildRule rule,
        RuleKeyBuilderFactory ruleKeyBuilderFactory,
        BuildRuleStatus status,
        CacheResult cacheResult,
        Optional<BuildRuleSuccessType> successType,
        Optional<HashCode> outputHash,
        Optional<Long> outputSize) {
      super(rule, ruleKeyBuilderFactory);
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
      String success = successType.isPresent() ? successType.get().toString() : "MISSING";
      return String.format("BuildRuleFinished(%s): %s %s %s %s",
          getBuildRule(),
          getStatus(),
          getCacheResult(),
          success,
          getRuleKeySafe());
    }

    @Override
    public String getEventName() {
      return "BuildRuleFinished";
    }
  }

  public static class Suspended extends BuildRuleEvent {

    protected Suspended(BuildRule rule, RuleKeyBuilderFactory ruleKeyBuilderFactory) {
      super(rule, ruleKeyBuilderFactory);
    }

    @Override
    public String getEventName() {
      return "BuildRuleSuspended";
    }

  }

  public static class Resumed extends BuildRuleEvent {

    protected Resumed(BuildRule rule, RuleKeyBuilderFactory ruleKeyBuilderFactory) {
      super(rule, ruleKeyBuilderFactory);
    }

    @Override
    public String getEventName() {
      return "BuildRuleResumed";
    }

  }

}
