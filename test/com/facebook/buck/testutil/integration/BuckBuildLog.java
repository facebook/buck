/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.testutil.integration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleStatus;
import com.facebook.buck.rules.BuildRuleSuccessType;
import com.facebook.buck.rules.CacheResult;
import com.facebook.buck.rules.Sha1HashCode;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuckBuildLog {

  private static final Pattern BUILD_LOG_FINISHED_RULE_REGEX =
      Pattern.compile(".*BuildRuleFinished\\((?<BuildTarget>[^\\)]+)\\): (?<Status>\\S+) " +
              "(?<CacheResult>\\S+) (?<SuccessType>\\S+) (?<RuleKey>\\S+)");

  private final Map<BuildTarget, BuildLogEntry> buildLogEntries;

  private BuckBuildLog(Map<BuildTarget, BuildLogEntry> buildLogEntries) {
    this.buildLogEntries = Preconditions.checkNotNull(buildLogEntries);
  }

  public void assertTargetBuiltLocally(String buildTargetRaw) {
    BuildLogEntry logEntry = getLogEntryOrFail(buildTargetRaw);
    assertEquals(BuildRuleSuccessType.BUILT_LOCALLY, logEntry.successType.get());
  }

  public void assertTargetWasFetchedFromCache(String buildTargetRaw) {
    BuildLogEntry logEntry = getLogEntryOrFail(buildTargetRaw);
    assertEquals(BuildRuleSuccessType.FETCHED_FROM_CACHE, logEntry.successType.get());
  }

  public void assertTargetHadMatchingInputRuleKey(String buildTargetRaw) {
    BuildLogEntry logEntry = getLogEntryOrFail(buildTargetRaw);
    assertThat(
        logEntry.successType.get(),
        equalTo(BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY));
  }

  public void assertTargetHadMatchingDepfileRuleKey(String buildTargetRaw) {
    BuildLogEntry logEntry = getLogEntryOrFail(buildTargetRaw);
    assertThat(
        logEntry.successType.get(),
        equalTo(BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY));
  }

  public void assertTargetHadMatchingDepsAbi(String buildTargetRaw) {
    BuildLogEntry logEntry = getLogEntryOrFail(buildTargetRaw);
    assertEquals(
        BuildRuleSuccessType.MATCHING_ABI_RULE_KEY,
        logEntry.successType.get());
  }

  public void assertTargetHadMatchingRuleKey(String buildTargetRaw) {
    BuildLogEntry logEntry = getLogEntryOrFail(buildTargetRaw);
    assertEquals(BuildRuleSuccessType.MATCHING_RULE_KEY, logEntry.successType.get());
  }

  public void assertTargetFailed(String buildTargetRaw) {
    BuildLogEntry logEntry = getLogEntryOrFail(buildTargetRaw);
    assertEquals(BuildRuleStatus.FAIL, logEntry.status);
  }

  public Sha1HashCode getRuleKey(String buildTargetRaw) {
    BuildLogEntry logEntry = getLogEntryOrFail(buildTargetRaw);
    return logEntry.ruleKey;
  }

  public ImmutableSet<BuildTarget> getAllTargets() {
    return ImmutableSet.copyOf(buildLogEntries.keySet());
  }

  public static BuckBuildLog fromLogContents(List<String> logContents) {
    ImmutableMap.Builder<BuildTarget, BuildLogEntry> builder = ImmutableMap.builder();

    for (String line : logContents) {
      Matcher matcher = BUILD_LOG_FINISHED_RULE_REGEX.matcher(line);
      if (!matcher.matches()) {
        continue;
      }

      String buildTargetRaw = matcher.group("BuildTarget");
      BuildTarget buildTarget = BuildTargetFactory.newInstance(buildTargetRaw);

      String statusRaw = matcher.group("Status");
      BuildRuleStatus status = BuildRuleStatus.valueOf(statusRaw);

      String ruleKeyRaw = matcher.group("RuleKey");
      Sha1HashCode ruleKey = Sha1HashCode.of(ruleKeyRaw);

      CacheResult cacheResult = null;
      BuildRuleSuccessType successType = null;

      if (status == BuildRuleStatus.SUCCESS) {
        String cacheResultRaw = matcher.group("CacheResult");
        cacheResult = CacheResult.valueOf(cacheResultRaw);

        String successTypeRaw = matcher.group("SuccessType");
        successType = BuildRuleSuccessType.valueOf(successTypeRaw);
      }

      builder.put(buildTarget, new BuildLogEntry(
              status,
              Optional.fromNullable(successType),
              Optional.fromNullable(cacheResult),
              ruleKey));
    }

    return new BuckBuildLog(builder.build());
  }

  private BuildLogEntry getLogEntryOrFail(String buildTargetRaw) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance(buildTargetRaw);
    if (!buildLogEntries.containsKey(buildTarget)) {
      fail(String.format("There was no build log entry for target %s", buildTargetRaw));
    }

    return buildLogEntries.get(buildTarget);
  }

  public BuildLogEntry getLogEntry(BuildTarget target) {
    return getLogEntryOrFail(target.toString());
  }

  public static class BuildLogEntry {

    private final BuildRuleStatus status;
    private final Optional<BuildRuleSuccessType> successType;
    private final Optional<CacheResult> cacheResult;
    private final Sha1HashCode ruleKey;

    private BuildLogEntry(
        BuildRuleStatus status,
        Optional<BuildRuleSuccessType> successType,
        Optional<CacheResult> cacheResult,
        Sha1HashCode ruleKey) {
      this.status = Preconditions.checkNotNull(status);
      this.successType = successType;
      this.cacheResult = cacheResult;
      this.ruleKey = Preconditions.checkNotNull(ruleKey);
    }

    public BuildRuleStatus getStatus() {
      return status;
    }

    public Optional<BuildRuleSuccessType> getSuccessType() {
      return successType;
    }

    public Optional<CacheResult> getCacheResult() {
      return cacheResult;
    }

    public Sha1HashCode getRuleKey() {
      return ruleKey;
    }

  }

}
