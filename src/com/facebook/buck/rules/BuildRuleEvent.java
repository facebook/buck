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
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.hash.HashCode;

import java.util.Optional;

/**
 * Base class for events about build rules.
 */
public abstract class BuildRuleEvent extends AbstractBuckEvent implements WorkAdvanceEvent {
  private final BuildRule rule;

  protected BuildRuleEvent(BuildRule rule) {
    super(EventKey.slowValueKey("BuildRuleEvent", rule.getFullyQualifiedName()));
    this.rule = rule;
  }

  public BuildRule getBuildRule() {
    return rule;
  }

  @Override
  public String getValueString() {
    return rule.getFullyQualifiedName();
  }

  public static Started started(BuildRule rule) {
    return new Started(rule);
  }

  public abstract boolean isRuleRunningAfterThisEvent();

  public static Finished finished(
      BuildRule rule,
      BuildRuleKeys ruleKeys,
      BuildRuleStatus status,
      CacheResult cacheResult,
      Optional<BuildRuleSuccessType> successType,
      Optional<HashCode> outputHash,
      Optional<Long> outputSize,
      Optional<Integer> inputsCount,
      Optional<Long> inputsSize) {
    return new Finished(
        rule,
        ruleKeys,
        status,
        cacheResult,
        successType,
        outputHash,
        outputSize,
        inputsCount,
        inputsSize);
  }

  public static Suspended suspended(
      BuildRule rule,
      RuleKeyBuilderFactory<RuleKey> ruleKeyBuilderFactory) {
    return new Suspended(rule, ruleKeyBuilderFactory);
  }

  public static Resumed resumed(
      BuildRule rule,
      RuleKeyBuilderFactory<RuleKey> ruleKeyBuilderFactory) {
    return new Resumed(rule, ruleKeyBuilderFactory);
  }

  public static class Started extends BuildRuleEvent {
    protected Started(BuildRule rule) {
      super(rule);
    }

    @Override
    public boolean isRuleRunningAfterThisEvent() {
      return true;
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
    private final BuildRuleKeys ruleKeys;
    private final Optional<HashCode> outputHash;
    private final Optional<Long> outputSize;
    private final Optional<Integer> inputsCount;
    private final Optional<Long> inputsSize;

    protected Finished(
        BuildRule rule,
        BuildRuleKeys ruleKeys,
        BuildRuleStatus status,
        CacheResult cacheResult,
        Optional<BuildRuleSuccessType> successType,
        Optional<HashCode> outputHash,
        Optional<Long> outputSize,
        Optional<Integer> inputsCount,
        Optional<Long> inputsSize) {
      super(rule);
      this.status = status;
      this.cacheResult = cacheResult;
      this.successType = successType;
      this.ruleKeys = ruleKeys;
      this.outputHash = outputHash;
      this.outputSize = outputSize;
      this.inputsCount = inputsCount;
      this.inputsSize = inputsSize;
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
    public BuildRuleKeys getRuleKeys() {
      return ruleKeys;
    }

    @JsonIgnore
    public Optional<HashCode> getOutputHash() {
      return outputHash;
    }

    @JsonIgnore
    public Optional<Long> getOutputSize() {
      return outputSize;
    }

    @JsonIgnore
    public Optional<Integer> getInputsCount() {
      return inputsCount;
    }

    @JsonIgnore
    public Optional<Long> getInputsSize() {
      return inputsSize;
    }

    @Override
    public String toString() {
      String success = successType.isPresent() ? successType.get().toString() : "MISSING";
      return String.format("BuildRuleFinished(%s): %s %s %s %s%s",
          getBuildRule(),
          getStatus(),
          getCacheResult(),
          success,
          getRuleKeys().getRuleKey().toString(),
          getRuleKeys().getInputRuleKey().isPresent() ?
              " I" + getRuleKeys().getInputRuleKey().get().toString() :
              "");
    }

    @Override
    public boolean isRuleRunningAfterThisEvent() {
      return false;
    }

    @Override
    public String getEventName() {
      return "BuildRuleFinished";
    }

    @JsonIgnore
    public String getResultString() {
      switch (getStatus()) {
        case SUCCESS:
          return getSuccessType().get().getShortDescription();
        case FAIL:
          return "FAILED";
        case CANCELED:
          return "CANCEL";
        default:
          return getStatus().toString();
      }
    }
  }

  public static class Suspended extends BuildRuleEvent {

    private final String ruleKey;

    protected Suspended(BuildRule rule, RuleKeyBuilderFactory<RuleKey> ruleKeyBuilderFactory) {
      super(rule);
      this.ruleKey = ruleKeyBuilderFactory.build(rule).toString();
    }

    @JsonIgnore
    public String getRuleKey() {
      return ruleKey;
    }

    @Override
    public boolean isRuleRunningAfterThisEvent() {
      return false;
    }

    @Override
    public String getEventName() {
      return "BuildRuleSuspended";
    }

  }

  public static class Resumed extends BuildRuleEvent {

    private final String ruleKey;

    protected Resumed(BuildRule rule, RuleKeyBuilderFactory<RuleKey> ruleKeyBuilderFactory) {
      super(rule);
      this.ruleKey = ruleKeyBuilderFactory.build(rule).toString();
    }

    @JsonIgnore
    public String getRuleKey() {
      return ruleKey;
    }

    @Override
    public boolean isRuleRunningAfterThisEvent() {
      return true;
    }

    @Override
    public String getEventName() {
      return "BuildRuleResumed";
    }
  }

  public static Scope startSuspendScope(
      BuckEventBus eventBus,
      BuildRule rule,
      RuleKeyBuilderFactory<RuleKey> ruleKeyBuilderFactory) {
    eventBus.post(BuildRuleEvent.started(rule));
    return new Scope(eventBus, () -> BuildRuleEvent.suspended(rule, ruleKeyBuilderFactory));
  }

  public static Scope resumeSuspendScope(
      BuckEventBus eventBus,
      BuildRule rule,
      RuleKeyBuilderFactory<RuleKey> ruleKeyBuilderFactory) {
    eventBus.post(BuildRuleEvent.resumed(rule, ruleKeyBuilderFactory));
    return new Scope(eventBus, () -> BuildRuleEvent.suspended(rule, ruleKeyBuilderFactory));
  }


}
