/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import static com.facebook.buck.log.MachineReadableLogConfig.PREFIX_BUILD_RULE_FINISHED;

import com.facebook.buck.artifact_cache.CacheResultType;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.ThrowingPrintWriter;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.annotations.VisibleForTesting;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.immutables.value.Value;

public class DistBuildPostBuildAnalysis {

  private static final String LINE_SEPARATOR = "----------------------------------------";

  private final BuildId buildId;
  private final StampedeId stampedeId;
  private final Path outputLogFile;
  private final Map<String, BuildRuleMachineLogEntry> localBuildRulesByName;
  private final Map<String, Map<String, BuildRuleMachineLogEntry>> remoteBuildRulesByRunIdAndName;

  public DistBuildPostBuildAnalysis(
      BuildId buildId,
      StampedeId stampedeId,
      Path logDirectoryPath,
      List<BuildSlaveRunId> remoteBuildRunIds,
      String distBuildCommandName)
      throws IOException {
    this.buildId = buildId;
    this.stampedeId = stampedeId;
    this.outputLogFile = logDirectoryPath.resolve(BuckConstant.DIST_BUILD_ANALYSIS_FILE_NAME);
    this.localBuildRulesByName =
        extractBuildRulesFromFile(
            logDirectoryPath.resolve(BuckConstant.BUCK_MACHINE_LOG_FILE_NAME));
    // Using LinkedHashMap to ensure we get the same order everytime.
    this.remoteBuildRulesByRunIdAndName = new LinkedHashMap<>();

    for (BuildSlaveRunId remoteBuildRunId : remoteBuildRunIds) {
      Path remoteBuildLogDir =
          DistBuildUtil.getRemoteBuckLogPath(remoteBuildRunId.id, logDirectoryPath);
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(remoteBuildLogDir)) {
        int numDistBuildInvocations = 0;
        for (Path file : stream) {
          if (file.toString().contains("_" + distBuildCommandName + "_")) {
            numDistBuildInvocations++;
            remoteBuildRulesByRunIdAndName.put(
                remoteBuildRunId.getId(),
                extractBuildRulesFromFile(file.resolve(BuckConstant.BUCK_MACHINE_LOG_FILE_NAME)));
          }
        }
        if (numDistBuildInvocations > 1) {
          throw new HumanReadableException(
              "Build is complete, but could not run post-distbuild analysis. "
                  + "Reason: Multiple invocations of DistBuildCommand found for [RunId = "
                  + remoteBuildRunId
                  + "].");
        }
      }
    }
  }

  private Map<String, BuildRuleMachineLogEntry> extractBuildRulesFromFile(Path machineLogFile)
      throws IOException {
    List<String> logLines = Files.readAllLines(machineLogFile);
    return extractBuildRules(logLines);
  }

  @VisibleForTesting
  static Map<String, BuildRuleMachineLogEntry> extractBuildRules(List<String> lines)
      throws IOException {
    List<String> ruleFinishedEvents =
        lines
            .stream()
            .filter(line -> line.startsWith(PREFIX_BUILD_RULE_FINISHED))
            .map(line -> line.substring(PREFIX_BUILD_RULE_FINISHED.length()))
            .collect(Collectors.toList());

    // Using LinkedHashMap to ensure we get the same order everytime.
    Map<String, BuildRuleMachineLogEntry> deserializedEventsByRuleName = new LinkedHashMap<>();
    for (String json : ruleFinishedEvents) {
      BuildRuleMachineLogEntry entry = BuildRuleMachineLogEntry.fromMachineReadableLogJson(json);
      if (entry.getRuleName().isPresent()) {
        deserializedEventsByRuleName.put(entry.getRuleName().get(), entry);
      }
    }
    return deserializedEventsByRuleName;
  }

  private PerRuleCumulativeStats analyseBuildRule(
      String ruleName, BuildRuleMachineLogEntry localRule) {
    PerRuleCumulativeStats.Builder result = PerRuleCumulativeStats.builder();

    result.setRuleName(ruleName);
    result.setRuleType(localRule.getRuleType().orElse(""));
    result.setLocalBuild(BuildRuleStats.of(localRule));

    Map<String, BuildRuleMachineLogEntry> remoteRulesByRunId = new HashMap<>();
    for (Map.Entry<String, Map<String, BuildRuleMachineLogEntry>> entry :
        remoteBuildRulesByRunIdAndName.entrySet()) {
      BuildRuleMachineLogEntry remoteRule = entry.getValue().get(ruleName);
      if (remoteRule != null) {
        remoteRulesByRunId.put(entry.getKey(), remoteRule);
      }
    }

    for (String runId : remoteRulesByRunId.keySet()) {
      BuildRuleMachineLogEntry remoteRule = remoteRulesByRunId.get(runId);
      BuildRuleStats.Builder remoteBuild = BuildRuleStats.builder();
      remoteBuild.setSlaveRunId(runId);
      remoteBuild.setLogEntry(remoteRule);

      boolean ruleKeyMismatch =
          localRule.getRuleKey().length() > 0
              && remoteRule.getRuleKey().length() > 0
              && !remoteRule.getRuleKey().equals(localRule.getRuleKey());
      boolean inputRuleKeyMismatch =
          localRule.getInputRuleKey().length() > 0
              && remoteRule.getInputRuleKey().length() > 0
              && !remoteRule.getInputRuleKey().equals(localRule.getInputRuleKey());

      remoteBuild.setWasDefaultRuleKeyMismatch(ruleKeyMismatch);
      remoteBuild.setWasInputRuleKeyMismatch(inputRuleKeyMismatch);

      result.addRemoteBuilds(remoteBuild.build());
    }

    return result.build();
  }

  public AnalysisResults runAnalysis() {
    AnalysisResults.Builder results = AnalysisResults.builder();

    // TODO(shivanker): Add per-slave cache statistics.
    for (Map.Entry<String, BuildRuleMachineLogEntry> entry : localBuildRulesByName.entrySet()) {
      results.addPerRuleStats(analyseBuildRule(entry.getKey(), entry.getValue()));
    }

    return results.build();
  }

  /** @return List of rule name/type pairs for all rule keys that mismatched */
  public List<RuleKeyNameAndType> getMismatchingDefaultRuleKeys(AnalysisResults results) {
    return results
        .perRuleStats()
        .stream()
        .filter(result -> result.wasDefaultRuleKeyMismatch())
        .map(
            result ->
                RuleKeyNameAndType.builder()
                    .setRuleName(result.ruleName())
                    .setRuleType(result.ruleType())
                    .build())
        .collect(Collectors.toList());
  }

  public Path dumpResultsToLogFile(AnalysisResults results) throws IOException {
    try (ThrowingPrintWriter writer =
        new ThrowingPrintWriter(new BufferedOutputStream(Files.newOutputStream(outputLogFile)))) {

      // General information.
      writer.printf("Build UUID: %s%n", buildId.toString());
      writer.printf("StampedeID: %s%n", stampedeId.getId());
      writer.printf(
          "BuildSlave Server RunIDs: %s%n",
          String.join(", ", remoteBuildRulesByRunIdAndName.keySet()));
      writer.println();

      // Summary stats.
      writer.printf(
          "Number of local CacheMisses after successful remote build: %d%n",
          results.numLocalCacheMissesWithSuccessfulRemoteBuild());
      writer.printf(
          "Number of Mismatching DefaultRuleKeys: %d%n", results.numMismatchingDefaultRuleKeys());
      writer.printf(
          "Number of Mismatching InputRuleKeys: %d%n", results.numMismatchingInputRuleKeys());
      writer.printf(
          "Number of BuildRule failures in local build: %d%n", results.numLocalFailedRules());
      writer.printf(
          "Number of BuildRule failures in remote build: %d%n", results.numRemoteFailedRules());
      writer.printf(
          "Number of BuildRules producing CacheMisses on multiple slaves: %d%n",
          results.numMultiSlaveCacheMisses());

      // Per-rule information.
      for (AbstractPerRuleCumulativeStats ruleStats : results.perRuleStats()) {
        writer.println();
        writer.println(LINE_SEPARATOR);
        writer.println();
        writer.printf(ruleStats.toString());
      }
    }
    return outputLogFile;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class MinimalBuildRuleKeys {
    @Nullable public RuleKey ruleKey;
    @Nullable public RuleKey inputRuleKey;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class MinimalBuildRuleInformation {
    public String name = "";
    public String type = "";
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class RuleDuration {
    public long wallMillisDuration = 0;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class RuleCacheResult {
    @Nullable public CacheResultType type;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  static class RuleFinishedEvent {
    public MinimalBuildRuleKeys ruleKeys = new MinimalBuildRuleKeys();
    public MinimalBuildRuleInformation buildRule = new MinimalBuildRuleInformation();
    public RuleDuration duration = new RuleDuration();
    @Nullable public BuildRuleStatus status;
    public RuleCacheResult cacheResult = new RuleCacheResult();
    public String outputHash = "";
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractBuildRuleMachineLogEntry {
    public abstract Optional<String> getRuleName();

    public abstract Optional<String> getRuleType();

    public abstract long getWallMillisDuration();

    public abstract String getRuleKey();

    public abstract String getInputRuleKey();

    public abstract Optional<BuildRuleStatus> getBuildRuleStatus();

    public abstract Optional<CacheResultType> getCacheResultType();

    public abstract String getOutputHash();

    public static BuildRuleMachineLogEntry fromMachineReadableLogJson(String json)
        throws IOException {
      BuildRuleMachineLogEntry.Builder builder = BuildRuleMachineLogEntry.builder();

      RuleFinishedEvent parsedJson =
          ObjectMappers.READER.readValue(
              ObjectMappers.createParser(json.getBytes(StandardCharsets.UTF_8)),
              new TypeReference<RuleFinishedEvent>() {});

      if (!parsedJson.buildRule.name.isEmpty()) {
        builder.setRuleName(parsedJson.buildRule.name);
      }

      if (!parsedJson.buildRule.type.isEmpty()) {
        builder.setRuleType(parsedJson.buildRule.type);
      }

      builder.setWallMillisDuration(parsedJson.duration.wallMillisDuration);

      if (parsedJson.ruleKeys.ruleKey == null) {
        throw new RuntimeException(
            String.format(
                "Invalid entry in machine-log file. Missing default rule-key for rule name: [%s].",
                parsedJson.buildRule.name));
      }
      builder.setRuleKey(parsedJson.ruleKeys.ruleKey.getHashCode().toString());

      if (parsedJson.ruleKeys.inputRuleKey != null) {
        builder.setInputRuleKey(parsedJson.ruleKeys.inputRuleKey.getHashCode().toString());
      } else {
        builder.setInputRuleKey("");
      }

      if (parsedJson.status != null) {
        builder.setBuildRuleStatus(parsedJson.status);
      }

      if (parsedJson.cacheResult.type != null) {
        builder.setCacheResultType(parsedJson.cacheResult.type);
      }

      builder.setOutputHash(parsedJson.outputHash);

      return builder.build();
    }

    @Value.Derived
    public boolean wasFailure() {
      return getBuildRuleStatus().equals(Optional.of(BuildRuleStatus.FAIL));
    }

    @Value.Derived
    public boolean wasSuccessful() {
      return getBuildRuleStatus().equals(Optional.of(BuildRuleStatus.SUCCESS));
    }

    @Value.Derived
    public boolean wasCanceled() {
      return getBuildRuleStatus().equals(Optional.of(BuildRuleStatus.CANCELED));
    }

    @Value.Derived
    public boolean wasCacheMiss() {
      return getCacheResultType().equals(Optional.of(CacheResultType.MISS));
    }

    @Override
    public String toString() {
      List<String> lines = new ArrayList<>();

      lines.add(String.format("Duration: %dms", getWallMillisDuration()));
      if (getCacheResultType().isPresent()) {
        lines.add(String.format("Cache Result: %s", getCacheResultType().get().toString()));
      }
      if (getBuildRuleStatus().isPresent()) {
        lines.add(String.format("Build Status: %s", getBuildRuleStatus().get().toString()));
      }

      if (getRuleKey().length() > 0) {
        lines.add(String.format("DefaultRuleKey: %s", getRuleKey()));
      }
      if (getInputRuleKey().length() > 0) {
        lines.add(String.format("InputRuleKey: %s", getInputRuleKey()));
      }
      return String.join(System.lineSeparator(), lines);
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractBuildRuleStats {

    @Value.Default
    public String slaveRunId() {
      return "local";
    }

    @Value.Default
    public boolean wasDefaultRuleKeyMismatch() {
      return false;
    }

    @Value.Default
    public boolean wasInputRuleKeyMismatch() {
      return false;
    }

    @Value.Parameter
    public abstract AbstractBuildRuleMachineLogEntry logEntry();

    @Override
    public String toString() {
      List<String> lines = new ArrayList<>();

      lines.add(String.format("Duration: %dms", logEntry().getWallMillisDuration()));
      if (logEntry().getCacheResultType().isPresent()) {
        lines.add(
            String.format("Cache Result: %s", logEntry().getCacheResultType().get().toString()));
      }
      if (logEntry().getBuildRuleStatus().isPresent()) {
        lines.add(
            String.format("Build Status: %s", logEntry().getBuildRuleStatus().get().toString()));
      }

      if (wasDefaultRuleKeyMismatch()) {
        lines.add(String.format("Mismatching DefaultRuleKey: %s", logEntry().getRuleKey()));
      }
      if (wasInputRuleKeyMismatch()) {
        lines.add(String.format("Mismatching InputRuleKey: %s", logEntry().getInputRuleKey()));
      }
      return String.join(System.lineSeparator(), lines);
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractPerRuleCumulativeStats {

    public abstract String ruleName();

    public abstract String ruleType();

    public abstract AbstractBuildRuleStats localBuild();

    public abstract List<AbstractBuildRuleStats> remoteBuilds();

    @Value.Derived
    public int numRemoteBuilds() {
      return remoteBuilds().size();
    }

    @Value.Lazy
    public boolean wasDefaultRuleKeyMismatch() {
      for (AbstractBuildRuleStats remote : remoteBuilds()) {
        if (remote.wasDefaultRuleKeyMismatch()) {
          return true;
        }
      }
      return false;
    }

    @Value.Lazy
    public boolean wasInputRuleKeyMismatch() {
      for (AbstractBuildRuleStats remote : remoteBuilds()) {
        if (remote.wasInputRuleKeyMismatch()) {
          return true;
        }
      }
      return false;
    }

    @Value.Lazy
    public boolean wasAnyRemoteSuccessful() {
      for (AbstractBuildRuleStats remote : remoteBuilds()) {
        if (remote.logEntry().wasSuccessful()) {
          return true;
        }
      }
      return false;
    }

    @Value.Lazy
    public boolean wasMissOnMultipleSlaves() {
      int count = 0;
      for (AbstractBuildRuleStats remote : remoteBuilds()) {
        if (remote.logEntry().wasCacheMiss()) {
          count++;
        }
      }
      return count > 1;
    }

    @Value.Derived
    public boolean wasLocalFailure() {
      return localBuild().logEntry().wasFailure();
    }

    @Value.Derived
    public boolean wasLocalCacheMiss() {
      return localBuild().logEntry().wasCacheMiss();
    }

    @Override
    public String toString() {
      List<String> lines = new ArrayList<>();

      lines.add(String.format("Rule Name: %s", ruleName()));
      lines.add(String.format("Rule Type: %s", ruleType()));
      lines.add("");

      lines.add("Local build:");
      lines.add(localBuild().logEntry().toString());
      lines.add(
          String.format(
              "Built by: %d slave servers [%s]",
              numRemoteBuilds(),
              remoteBuilds().stream().map(b -> b.slaveRunId()).collect(Collectors.joining(", "))));

      for (AbstractBuildRuleStats build : remoteBuilds()) {
        lines.add("");
        lines.add(String.format("Slave Build: (RunId %s)", build.slaveRunId()));
        lines.add(build.toString());
      }

      if (wasMissOnMultipleSlaves()) {
        lines.add("");
        lines.add("THIS RULE WAS A CACHE MISS ON MULTIPLE BUILD-SLAVES.");
      }

      lines.add("");
      return String.join(System.lineSeparator(), lines);
    }
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractAnalysisResults {

    public abstract List<AbstractPerRuleCumulativeStats> perRuleStats();

    @Value.Lazy
    public int numLocalCacheMissesWithSuccessfulRemoteBuild() {
      int count = 0;
      for (AbstractPerRuleCumulativeStats rule : perRuleStats()) {
        if (rule.wasAnyRemoteSuccessful() && rule.wasLocalCacheMiss()) {
          count++;
        }
      }
      return count;
    }

    @Value.Lazy
    public int numMismatchingDefaultRuleKeys() {
      int count = 0;
      for (AbstractPerRuleCumulativeStats rule : perRuleStats()) {
        if (rule.wasDefaultRuleKeyMismatch()) {
          count++;
        }
      }
      return count;
    }

    @Value.Lazy
    public int numMismatchingInputRuleKeys() {
      int count = 0;
      for (AbstractPerRuleCumulativeStats rule : perRuleStats()) {
        if (rule.wasInputRuleKeyMismatch()) {
          count++;
        }
      }
      return count;
    }

    @Value.Lazy
    public int numLocalFailedRules() {
      int count = 0;
      for (AbstractPerRuleCumulativeStats rule : perRuleStats()) {
        if (rule.wasLocalFailure()) {
          count++;
        }
      }
      return count;
    }

    @Value.Lazy
    public int numRemoteFailedRules() {
      int count = 0;
      for (AbstractPerRuleCumulativeStats rule : perRuleStats()) {
        if (!rule.wasAnyRemoteSuccessful()) {
          count++;
        }
      }
      return count;
    }

    @Value.Lazy
    public int numMultiSlaveCacheMisses() {
      int count = 0;
      for (AbstractPerRuleCumulativeStats rule : perRuleStats()) {
        if (rule.wasMissOnMultipleSlaves()) {
          count++;
        }
      }
      return count;
    }
  }

  /** Pair containing name and type of a rule key. */
  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractRuleKeyNameAndType {
    abstract String getRuleName();

    abstract String getRuleType();
  }
}
