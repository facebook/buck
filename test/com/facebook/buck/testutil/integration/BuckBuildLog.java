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
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuckBuildLog {

  private static final Pattern BUILD_LOG_FINISHED_RULE_REGEX =
      Pattern.compile(
          ".*BuildRuleFinished\\((?<BuildTarget>[^\\)]+)\\): (?<Status>\\S+) "
              + "(?<CacheResult>\\S+) (?<SuccessType>\\S+) (?<RuleKey>\\S+)"
              + "(?: I(?<InputRuleKey>\\S+))?");

  private final Path root;
  private final Map<BuildTarget, BuildLogEntry> buildLogEntries;

  private BuckBuildLog(Path root, Map<BuildTarget, BuildLogEntry> buildLogEntries) {
    this.root = root;
    this.buildLogEntries = Preconditions.checkNotNull(buildLogEntries);
  }

  public void assertTargetBuiltLocally(String buildTargetRaw) {
    assertBuildSuccessType(buildTargetRaw, BuildRuleSuccessType.BUILT_LOCALLY);
  }

  public void assertNotTargetBuiltLocally(String buildTargetRaw) {
    BuildLogEntry logEntry = getLogEntry(buildTargetRaw);
    assertNotEquals(
        String.format(
            "Build target %s should not have been built locally, but it was", buildTargetRaw),
        BuildRuleSuccessType.BUILT_LOCALLY,
        logEntry.successType.get());
  }

  public void assertTargetIsAbsent(String buildTargetRaw) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance(root, buildTargetRaw);
    if (buildLogEntries.containsKey(buildTarget)) {
      fail(
          String.format(
              "Build target %s was not expected in log, but found result: %s",
              buildTargetRaw, buildLogEntries.get(buildTarget)));
    }
  }

  public void assertTargetWasFetchedFromCache(String buildTargetRaw) {
    assertBuildSuccessType(buildTargetRaw, BuildRuleSuccessType.FETCHED_FROM_CACHE);
  }

  public void assertTargetWasFetchedFromCacheByManifestMatch(String buildTargetRaw) {
    assertBuildSuccessType(buildTargetRaw, BuildRuleSuccessType.FETCHED_FROM_CACHE_MANIFEST_BASED);
  }

  public void assertTargetHadMatchingInputRuleKey(String buildTargetRaw) {
    assertBuildSuccessType(buildTargetRaw, BuildRuleSuccessType.MATCHING_INPUT_BASED_RULE_KEY);
  }

  public void assertTargetHadMatchingDepfileRuleKey(String buildTargetRaw) {
    assertBuildSuccessType(buildTargetRaw, BuildRuleSuccessType.MATCHING_DEP_FILE_RULE_KEY);
  }

  public void assertTargetHadMatchingRuleKey(String buildTargetRaw) {
    assertBuildSuccessType(buildTargetRaw, BuildRuleSuccessType.MATCHING_RULE_KEY);
  }

  public void assertTargetFailed(String buildTargetRaw) {
    BuildLogEntry logEntry = getLogEntry(buildTargetRaw);
    assertEquals(BuildRuleStatus.FAIL, logEntry.status);
  }

  public void assertTargetCanceled(String buildTargetRaw) {
    BuildLogEntry logEntry = getLogEntry(buildTargetRaw);
    assertEquals(BuildRuleStatus.CANCELED, logEntry.status);
  }

  public Sha1HashCode getRuleKey(String buildTargetRaw) {
    BuildLogEntry logEntry = getLogEntry(buildTargetRaw);
    return logEntry.ruleKeyHashCode;
  }

  public ImmutableSet<BuildTarget> getAllTargets() {
    return ImmutableSortedSet.copyOf(buildLogEntries.keySet());
  }

  public static BuckBuildLog fromLogContents(Path root, List<String> logContents) {
    ImmutableMap.Builder<BuildTarget, BuildLogEntry> builder = ImmutableMap.builder();

    for (String line : logContents) {
      Matcher matcher = BUILD_LOG_FINISHED_RULE_REGEX.matcher(line);
      if (!matcher.matches()) {
        continue;
      }

      String buildTargetRaw = matcher.group("BuildTarget");
      BuildTarget buildTarget = BuildTargetFactory.newInstance(root, buildTargetRaw);

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

      builder.put(
          buildTarget,
          new BuildLogEntry(
              status, Optional.ofNullable(successType), Optional.ofNullable(cacheResult), ruleKey));
    }

    return new BuckBuildLog(root, builder.build());
  }

  private void assertBuildSuccessType(String buildTargetRaw, BuildRuleSuccessType expectedType) {
    BuildLogEntry logEntry = getLogEntry(buildTargetRaw);
    assertThat(
        String.format("%s should have succeeded", buildTargetRaw),
        logEntry.getStatus(),
        is(BuildRuleStatus.SUCCESS));
    assertThat(
        String.format("%s has %s", buildTargetRaw, logEntry),
        logEntry.successType.get(),
        is(expectedType));
  }

  public void assertNoLogEntry(String buildTargetRaw) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance(root, buildTargetRaw);
    if (buildLogEntries.containsKey(buildTarget)) {
      fail(
          String.format(
              "Was expecting no log entry for %s, but found: %s",
              buildTargetRaw, buildLogEntries.get(buildTarget)));
    }
  }

  public BuildLogEntry getLogEntry(String buildTargetRaw) {
    BuildTarget buildTarget = BuildTargetFactory.newInstance(root, buildTargetRaw);
    if (!buildLogEntries.containsKey(buildTarget)) {
      fail(String.format("There was no build log entry for target %s", buildTargetRaw));
    }

    return buildLogEntries.get(buildTarget);
  }

  public BuildLogEntry getLogEntry(BuildTarget target) {
    return getLogEntry(target.toString());
  }

  public static class BuildLogEntry {

    private final BuildRuleStatus status;
    private final Optional<BuildRuleSuccessType> successType;
    private final Optional<CacheResult> cacheResult;
    private final Sha1HashCode ruleKeyHashCode;

    private BuildLogEntry(
        BuildRuleStatus status,
        Optional<BuildRuleSuccessType> successType,
        Optional<CacheResult> cacheResult,
        Sha1HashCode ruleKeyHashCode) {
      this.status = Preconditions.checkNotNull(status);
      this.successType = successType;
      this.cacheResult = cacheResult;
      this.ruleKeyHashCode = Preconditions.checkNotNull(ruleKeyHashCode);
    }

    public BuildRuleStatus getStatus() {
      return status;
    }

    public Optional<BuildRuleSuccessType> getSuccessType() {
      return successType;
    }

    public Sha1HashCode getRuleKey() {
      return ruleKeyHashCode;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("status", status)
          .add("successType", successType)
          .add("cacheResult", cacheResult)
          .add("ruleKeyHashCode", ruleKeyHashCode)
          .toString();
    }
  }
}
