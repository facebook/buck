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

package com.facebook.buck.core.build.event;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.manifest.ManifestFetchResult;
import com.facebook.buck.core.build.engine.manifest.ManifestStoreResult;
import com.facebook.buck.core.build.stats.BuildRuleDiagnosticData;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.event.AbstractBuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.EventKey;
import com.facebook.buck.event.RuleKeyCalculationEvent;
import com.facebook.buck.event.WorkAdvanceEvent;
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.keys.RuleKeyFactory;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.timing.ClockDuration;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonView;
import com.google.common.base.Preconditions;
import com.google.common.hash.HashCode;
import java.util.Optional;
import javax.annotation.Nullable;

/** Base class for events about build rules. */
public abstract class BuildRuleEvent extends AbstractBuckEvent implements WorkAdvanceEvent {

  private final BuildRule rule;

  /** Accumulated duration of work spent on this rule up until this event occurred. */
  @Nullable protected ClockDuration duration;

  protected BuildRuleEvent(EventKey eventKey, BuildRule rule) {
    super(eventKey);
    this.rule = rule;
  }

  @JsonView(JsonViews.MachineReadableLog.class)
  public BuildRule getBuildRule() {
    return rule;
  }

  @JsonView(JsonViews.MachineReadableLog.class)
  public ClockDuration getDuration() {
    Preconditions.checkState(isConfigured(), "Event was not configured yet.");
    return Preconditions.checkNotNull(duration);
  }

  @Override
  public String getValueString() {
    return rule.getFullyQualifiedName();
  }

  @Override
  public final String getEventName() {
    return "BuildRule" + getClass().getSimpleName();
  }

  public abstract boolean isRuleRunningAfterThisEvent();

  private static EventKey createEventKey(BuildRule rule) {
    return EventKey.slowValueKey("BuildRuleEvent", rule.getFullyQualifiedName());
  }

  public static Started started(BuildRule rule, BuildRuleDurationTracker tracker) {
    return new Started(createEventKey(rule), rule, tracker);
  }

  public static Finished finished(
      BeginningBuildRuleEvent beginning,
      BuildRuleKeys ruleKeys,
      BuildRuleStatus status,
      CacheResult cacheResult,
      Optional<BuildId> origin,
      Optional<BuildRuleSuccessType> successType,
      boolean willTryUploadToCache,
      Optional<HashCode> outputHash,
      Optional<Long> outputSize,
      Optional<BuildRuleDiagnosticData> diagnosticData,
      Optional<ManifestFetchResult> manifestFetchResult,
      Optional<ManifestStoreResult> manifestStoreResult) {
    return new Finished(
        beginning,
        ruleKeys,
        status,
        cacheResult,
        origin,
        successType,
        willTryUploadToCache,
        outputHash,
        outputSize,
        diagnosticData,
        manifestFetchResult,
        manifestStoreResult);
  }

  public static StartedRuleKeyCalc ruleKeyCalculationStarted(
      BuildRule rule, BuildRuleDurationTracker tracker) {
    return new StartedRuleKeyCalc(createEventKey(rule), rule, tracker);
  }

  public static FinishedRuleKeyCalc ruleKeyCalculationFinished(
      StartedRuleKeyCalc started, RuleKeyFactory<RuleKey> ruleKeyFactory) {
    return new FinishedRuleKeyCalc(started, ruleKeyFactory);
  }

  public static Suspended suspended(
      BeginningBuildRuleEvent beginning, RuleKeyFactory<RuleKey> ruleKeyFactory) {
    return new Suspended(beginning, ruleKeyFactory);
  }

  public static Resumed resumed(
      BuildRule rule, BuildRuleDurationTracker tracker, RuleKeyFactory<RuleKey> ruleKeyFactory) {
    return new Resumed(createEventKey(rule), rule, tracker, ruleKeyFactory);
  }

  public static WillBuildLocally willBuildLocally(BuildRule rule) {
    return new WillBuildLocally(rule);
  }

  /**
   * A {@link BuildRuleEvent} that denotes beginning of computation for a particular {@link
   * BuildRule}.
   */
  public abstract static class BeginningBuildRuleEvent extends BuildRuleEvent {

    @JsonIgnore private final BuildRuleDurationTracker tracker;

    public BeginningBuildRuleEvent(
        EventKey eventKey, BuildRule rule, BuildRuleDurationTracker tracker) {
      super(eventKey, rule);
      this.tracker = tracker;
    }

    @Override
    public void configure(
        long timestamp, long nanoTime, long threadUserNanoTime, long threadId, BuildId buildId) {
      super.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
      this.duration = tracker.doBeginning(getBuildRule(), timestamp, nanoTime);
    }

    @Override
    public final boolean isRuleRunningAfterThisEvent() {
      return true;
    }
  }

  /**
   * A {@link BuildRuleEvent} that denotes ending of computation for a particular {@link BuildRule}.
   */
  public abstract static class EndingBuildRuleEvent extends BuildRuleEvent {

    @JsonIgnore private final BeginningBuildRuleEvent beginning;

    public EndingBuildRuleEvent(BeginningBuildRuleEvent beginning) {
      super(beginning.getEventKey(), beginning.getBuildRule());
      this.beginning = beginning;
    }

    @JsonIgnore
    public BeginningBuildRuleEvent getBeginningEvent() {
      return beginning;
    }

    @Override
    public void configure(
        long timestamp, long nanoTime, long threadUserNanoTime, long threadId, BuildId buildId) {
      super.configure(timestamp, nanoTime, threadUserNanoTime, threadId, buildId);
      long threadUserNanoDuration = threadUserNanoTime - beginning.getThreadUserNanoTime();
      this.duration =
          beginning.tracker.doEnding(getBuildRule(), timestamp, nanoTime, threadUserNanoDuration);
    }

    @Override
    public final boolean isRuleRunningAfterThisEvent() {
      return false;
    }
  }

  /**
   * Marks the start of processing a build rule. We keep this as a distinct base class for
   * subscribers who want to process start/rule-key-calc events separately.
   */
  public static class Started extends BeginningBuildRuleEvent {

    private Started(EventKey eventKey, BuildRule rule, BuildRuleDurationTracker tracker) {
      super(eventKey, rule, tracker);
    }
  }

  /** Marks the end of processing a build rule. */
  public static class Finished extends EndingBuildRuleEvent {

    private final BuildRuleStatus status;
    private final CacheResult cacheResult;
    private final Optional<BuildId> origin;
    private final Optional<BuildRuleSuccessType> successType;
    private final boolean willTryUploadToCache;
    private final BuildRuleKeys ruleKeys;
    private final Optional<HashCode> outputHash;
    private final Optional<Long> outputSize;
    private final Optional<BuildRuleDiagnosticData> diagnosticData;
    private final Optional<ManifestFetchResult> manifestFetchResult;
    private final Optional<ManifestStoreResult> manifestStoreResult;

    private Finished(
        BeginningBuildRuleEvent beginning,
        BuildRuleKeys ruleKeys,
        BuildRuleStatus status,
        CacheResult cacheResult,
        Optional<BuildId> origin,
        Optional<BuildRuleSuccessType> successType,
        boolean willTryUploadToCache,
        Optional<HashCode> outputHash,
        Optional<Long> outputSize,
        Optional<BuildRuleDiagnosticData> diagnosticData,
        Optional<ManifestFetchResult> manifestFetchResult,
        Optional<ManifestStoreResult> manifestStoreResult) {
      super(beginning);
      this.status = status;
      this.cacheResult = cacheResult;
      this.origin = origin;
      this.successType = successType;
      this.willTryUploadToCache = willTryUploadToCache;
      this.ruleKeys = ruleKeys;
      this.outputHash = outputHash;
      this.outputSize = outputSize;
      this.diagnosticData = diagnosticData;
      this.manifestFetchResult = manifestFetchResult;
      this.manifestStoreResult = manifestStoreResult;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public BuildRuleStatus getStatus() {
      return status;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public CacheResult getCacheResult() {
      return cacheResult;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public Optional<BuildId> getOrigin() {
      return origin;
    }

    @JsonIgnore
    public Optional<BuildRuleSuccessType> getSuccessType() {
      return successType;
    }

    @JsonView(JsonViews.MachineReadableLog.class)
    public Optional<String> getSuccessTypeName() {
      return successType.isPresent() ? Optional.of(successType.get().name()) : Optional.empty();
    }

    @JsonIgnore
    public boolean isWillTryUploadToCache() {
      return willTryUploadToCache;
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

    @JsonIgnore
    public Optional<BuildRuleDiagnosticData> getDiagnosticData() {
      return diagnosticData;
    }

    @JsonIgnore
    public Optional<ManifestFetchResult> getManifestFetchResult() {
      return manifestFetchResult;
    }

    @JsonIgnore
    public Optional<ManifestStoreResult> getManifestStoreResult() {
      return manifestStoreResult;
    }

    @JsonIgnore
    public boolean isBuildRuleNoOp() {
      return getBuildRule() instanceof NoopBuildRule
          || getBuildRule() instanceof NoopBuildRuleWithDeclaredAndExtraDeps;
    }

    @Override
    public String toString() {
      String success = successType.isPresent() ? successType.get().toString() : "MISSING";
      return String.format(
          "BuildRuleFinished(%s): %s %s %s %s%s",
          getBuildRule().getFullyQualifiedName(),
          getStatus(),
          getCacheResult(),
          success,
          getRuleKeys().getRuleKey().toString(),
          getRuleKeys().getInputRuleKey().isPresent()
              ? " I" + getRuleKeys().getInputRuleKey().get()
              : "");
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

  /** Marks a rule is suspended from processing. */
  public static class Suspended extends EndingBuildRuleEvent {

    private final String ruleKey;

    private Suspended(BeginningBuildRuleEvent beginning, RuleKeyFactory<RuleKey> ruleKeyFactory) {
      super(beginning);
      this.ruleKey = ruleKeyFactory.build(beginning.getBuildRule()).toString();
    }

    @JsonIgnore
    public String getRuleKey() {
      return ruleKey;
    }
  }

  /** Marks the continuation of processing a rule. */
  public static class Resumed extends BeginningBuildRuleEvent {

    private final String ruleKey;

    private Resumed(
        EventKey eventKey,
        BuildRule rule,
        BuildRuleDurationTracker tracker,
        RuleKeyFactory<RuleKey> ruleKeyFactory) {
      super(eventKey, rule, tracker);
      this.ruleKey = ruleKeyFactory.build(rule).toString();
    }

    @JsonIgnore
    public String getRuleKey() {
      return ruleKey;
    }
  }

  /**
   * Marks the start of processing a rule to calculate its rule key. We overload this as both a rule
   * start and rule key calc start event to generate less events and be more efficient.
   */
  public static class StartedRuleKeyCalc extends Started
      implements RuleKeyCalculationEvent.Started {

    private StartedRuleKeyCalc(
        EventKey eventKey, BuildRule rule, BuildRuleDurationTracker tracker) {
      super(eventKey, rule, tracker);
    }

    @Override
    public Type getType() {
      return Type.NORMAL;
    }

    @Override
    public BuildTarget getTarget() {
      return getBuildRule().getBuildTarget();
    }
  }

  /**
   * Marks the completion of processing a rule to calculate its rule key. We overload this as both a
   * rule suspend and rule key calc finish event to generate less events and be more efficient.
   */
  public static class FinishedRuleKeyCalc extends Suspended
      implements RuleKeyCalculationEvent.Finished {

    private FinishedRuleKeyCalc(
        StartedRuleKeyCalc started, RuleKeyFactory<RuleKey> ruleKeyFactory) {
      super(started, ruleKeyFactory);
    }

    @Override
    public Type getType() {
      return Type.NORMAL;
    }

    @Override
    public BuildTarget getTarget() {
      return getBuildRule().getBuildTarget();
    }
  }

  /** Denotes that a particular build rule will be built locally. */
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
      BuildRuleDurationTracker tracker,
      RuleKeyFactory<RuleKey> ruleKeyFactory) {
    StartedRuleKeyCalc started = ruleKeyCalculationStarted(rule, tracker);
    eventBus.post(started);
    return () -> eventBus.post(ruleKeyCalculationFinished(started, ruleKeyFactory));
  }
}
