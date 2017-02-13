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
import com.facebook.buck.event.RuleKeyCalculationEvent;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.rules.keys.RuleKeyFactory;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.hash.HashCode;

import java.util.Optional;

/**
 * Base class for events about build rules.
 */
public abstract class BuildRuleEvent extends AbstractBuckEvent implements WorkAdvanceEvent {

  private final BuildRule rule;

  protected BuildRuleEvent(EventKey eventKey, BuildRule rule) {
    super(eventKey);
    this.rule = rule;
  }

  protected BuildRuleEvent(BuildRule rule) {
    this(EventKey.slowValueKey("BuildRuleEvent", rule.getFullyQualifiedName()), rule);
  }

  @JsonView(JsonViews.MachineReadableLog.class)
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

  public static Started ruleKeyCalculationStarted(BuildRule rule) {
    return new StartedRuleKeyCalc(rule);
  }

  @Override
  public final String getEventName() {
    return "BuildRule" + getClass().getSimpleName();
  }

  public abstract boolean isRuleRunningAfterThisEvent();

  public static Finished finished(
      BuildRule rule,
      BuildRuleKeys ruleKeys,
      BuildRuleStatus status,
      CacheResult cacheResult,
      Optional<BuildRuleSuccessType> successType,
      Optional<HashCode> outputHash,
      Optional<Long> outputSize) {
    return new Finished(
        rule,
        ruleKeys,
        status,
        cacheResult,
        successType,
        outputHash,
        outputSize);
  }

  public static Suspended suspended(
      BuildRule rule,
      RuleKeyFactory<RuleKey> ruleKeyFactory) {
    return new Suspended(rule, ruleKeyFactory);
  }

  public static Resumed resumed(
      BuildRule rule,
      RuleKeyFactory<RuleKey> ruleKeyFactory) {
    return new Resumed(rule, ruleKeyFactory);
  }

  public static WillBuildLocally willBuildLocally(
      BuildRule rule) {
    return new WillBuildLocally(rule);
  }

  /**
   * Marks the start of processing a build rule.  We keep this as a distinct base class for
   * subscribers who want to process start/rule-key-calc events separately.
   */
  public static class Started extends BuildRuleEvent {

    private Started(BuildRule rule) {
      super(rule);
    }

    @Override
    public final boolean isRuleRunningAfterThisEvent() {
      return true;
    }

  }

  /**
   * Marks the end of processing a build rule.
   */
  public static class Finished extends BuildRuleEvent {

    private final BuildRuleStatus status;
    private final CacheResult cacheResult;
    private final Optional<BuildRuleSuccessType> successType;
    private final BuildRuleKeys ruleKeys;
    private final Optional<HashCode> outputHash;
    private final Optional<Long> outputSize;

    protected Finished(
        BuildRule rule,
        BuildRuleKeys ruleKeys,
        BuildRuleStatus status,
        CacheResult cacheResult,
        Optional<BuildRuleSuccessType> successType,
        Optional<HashCode> outputHash,
        Optional<Long> outputSize) {
      super(rule);
      this.status = status;
      this.cacheResult = cacheResult;
      this.successType = successType;
      this.ruleKeys = ruleKeys;
      this.outputHash = outputHash;
      this.outputSize = outputSize;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public BuildRuleStatus getStatus() {
      return status;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public CacheResult getCacheResult() {
      return cacheResult;
    }

    @JsonIgnore
    public Optional<BuildRuleSuccessType> getSuccessType() {
      return successType;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public BuildRuleKeys getRuleKeys() {
      return ruleKeys;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
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

  /**
   * Marks a rule is suspended from processing.
   */
  public static class Suspended extends BuildRuleEvent {

    private final String ruleKey;

    private Suspended(EventKey eventKey, BuildRule rule, RuleKeyFactory<RuleKey> ruleKeyFactory) {
      super(eventKey, rule);
      this.ruleKey = ruleKeyFactory.build(rule).toString();
    }

    private Suspended(BuildRule rule, RuleKeyFactory<RuleKey> ruleKeyFactory) {
      super(rule);
      this.ruleKey = ruleKeyFactory.build(rule).toString();
    }

    @JsonIgnore
    public String getRuleKey() {
      return ruleKey;
    }

    @Override
    public boolean isRuleRunningAfterThisEvent() {
      return false;
    }

  }

  /**
   * Marks the continuation of processing a rule.
   */
  public static class Resumed extends BuildRuleEvent {

    private final String ruleKey;

    protected Resumed(BuildRule rule, RuleKeyFactory<RuleKey> ruleKeyFactory) {
      super(rule);
      this.ruleKey = ruleKeyFactory.build(rule).toString();
    }

    @JsonIgnore
    public String getRuleKey() {
      return ruleKey;
    }

    @Override
    public boolean isRuleRunningAfterThisEvent() {
      return true;
    }

  }

  /**
   * Marks the start of processing a rule to calculate its rule key.
   */
  private static class StartedRuleKeyCalc extends Started implements RuleKeyCalculationEvent {

    private StartedRuleKeyCalc(BuildRule rule) {
      super(rule);
    }

    @Override
    public Type getType() {
      return Type.NORMAL;
    }

  }

  /**
   * Marks the completion of processing a rule to calculate its rule key.
   */
  private static class FinishedRuleKeyCalc extends Suspended implements RuleKeyCalculationEvent {

    private FinishedRuleKeyCalc(
        StartedRuleKeyCalc started,
        RuleKeyFactory<RuleKey> ruleKeyFactory) {
      super(started.getEventKey(), started.getBuildRule(), ruleKeyFactory);
    }

    @Override
    public Type getType() {
      return Type.NORMAL;
    }

  }

  public static class WillBuildLocally extends AbstractBuckEvent implements WorkAdvanceEvent {

    private final BuildRule rule;

    public WillBuildLocally(BuildRule rule) {
      super(EventKey.unique());
      this.rule = rule;
    }

    @Override
    public String getEventName() {
      return "BuildRuleWillBuildLocally";
    }

    @Override
    protected String getValueString() {
      return rule.toString();
    }
  }

  public static Scope ruleKeyCalculationScope(
      BuckEventBus eventBus,
      BuildRule rule,
      RuleKeyFactory<RuleKey> ruleKeyFactory) {
    StartedRuleKeyCalc started = new StartedRuleKeyCalc(rule);
    eventBus.post(started);
    return new Scope(eventBus, () -> new FinishedRuleKeyCalc(started, ruleKeyFactory));
  }

  public static Scope resumeSuspendScope(
      BuckEventBus eventBus,
      BuildRule rule,
      RuleKeyFactory<RuleKey> ruleKeyFactory) {
    eventBus.post(BuildRuleEvent.resumed(rule, ruleKeyFactory));
    return new Scope(eventBus, () -> BuildRuleEvent.suspended(rule, ruleKeyFactory));
  }

}
