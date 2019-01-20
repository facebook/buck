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

import com.facebook.buck.artifact_cache.CacheResult;
import com.facebook.buck.cli.DistBuildCommand;
import com.facebook.buck.core.build.engine.BuildRuleStatus;
import com.facebook.buck.core.build.engine.BuildRuleSuccessType;
import com.facebook.buck.core.build.engine.type.UploadToCacheResultType;
import com.facebook.buck.core.build.event.BuildRuleEvent;
import com.facebook.buck.core.build.stats.BuildRuleDurationTracker;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rulekey.BuildRuleKeys;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.log.views.JsonViews;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class DistBuildPostBuildAnalysisTest {

  @Rule public TemporaryPaths tmpPath = new TemporaryPaths();

  private final BuildId buildId = new BuildId("97eb735b-27c9-4d60-9564-fb0694a53b03");
  private final StampedeId stampedeId = new StampedeId();
  private final BuildSlaveRunId buildSlaveRunId1 = new BuildSlaveRunId();
  private final BuildSlaveRunId buildSlaveRunId2 = new BuildSlaveRunId();

  private ObjectWriter objectWriter;

  @Before
  public void setUp() {
    stampedeId.setId("stampede5057850940756389804");
    buildSlaveRunId1.setId("stampede-slave1241653611715395998");
    buildSlaveRunId2.setId("stampede-slave8890909885267341364");

    objectWriter =
        ObjectMappers.legacyCreate()
            .copy()
            .configure(MapperFeature.DEFAULT_VIEW_INCLUSION, false)
            .writerWithView(JsonViews.MachineReadableLog.class);
  }

  private String buildRuleFinishedAsMachineReadable(BuildRuleEvent.Finished event) {
    try {
      return PREFIX_BUILD_RULE_FINISHED + " " + objectWriter.writeValueAsString(event);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to write BuildRuleEvent.Finished to JSON.", e);
    }
  }

  private BuildRuleEvent.Finished finishedEvent(
      String ruleName,
      String ruleKey,
      Optional<String> inputRuleKey,
      BuildRuleStatus status,
      CacheResult cacheResult,
      long durationMs,
      Optional<BuildRuleSuccessType> successType,
      Optional<String> outputHash) {
    FakeBuildRule rule = new FakeBuildRule(ruleName);
    BuildRuleEvent.Started start = BuildRuleEvent.started(rule, new BuildRuleDurationTracker());

    BuildRuleKeys.Builder ruleKeys = BuildRuleKeys.builder().setRuleKey(new RuleKey(ruleKey));
    if (inputRuleKey.isPresent()) {
      ruleKeys.setInputRuleKey(new RuleKey(inputRuleKey.get()));
    }

    BuildRuleEvent.Finished finishedEvent =
        BuildRuleEvent.finished(
            start,
            ruleKeys.build(),
            status,
            cacheResult,
            Optional.empty(),
            successType,
            UploadToCacheResultType.UNCACHEABLE,
            outputHash.map(s -> HashCode.fromString(s)),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    finishedEvent.configure(durationMs, 0, 0, 0, buildId);
    return finishedEvent;
  }

  @Test
  public void testExtractBuildRules() throws IOException {
    List<BuildRuleEvent.Finished> ruleEvents = new ArrayList<>();
    ruleEvents.add(
        finishedEvent(
            "//rule:1",
            "12ea",
            Optional.empty(),
            BuildRuleStatus.FAIL,
            CacheResult.localKeyUnchangedHit(),
            10,
            Optional.empty(),
            Optional.empty()));

    ruleEvents.add(
        finishedEvent(
            "//rule:2",
            "2ea5",
            Optional.of("ea56"),
            BuildRuleStatus.SUCCESS,
            CacheResult.miss(),
            50,
            Optional.of(BuildRuleSuccessType.BUILT_LOCALLY),
            Optional.of("a567")));

    Map<String, BuildRuleMachineLogEntry> resultMap =
        DistBuildPostBuildAnalysis.extractBuildRules(
            ruleEvents
                .stream()
                .map(event -> buildRuleFinishedAsMachineReadable(event))
                .collect(Collectors.toList()));

    Assert.assertEquals(2, resultMap.keySet().size());
    Assert.assertTrue(
        resultMap
            .keySet()
            .containsAll(
                ruleEvents
                    .stream()
                    .map(rule -> rule.getBuildRule().getFullyQualifiedName())
                    .collect(Collectors.toList())));

    for (BuildRuleEvent.Finished expected : ruleEvents) {
      BuildRuleMachineLogEntry result =
          resultMap.get(expected.getBuildRule().getFullyQualifiedName());

      Assert.assertEquals(
          result.getRuleName().get(), expected.getBuildRule().getFullyQualifiedName());
      Assert.assertEquals(result.getRuleKey(), expected.getRuleKeys().getRuleKey().toString());
      Assert.assertEquals(
          result.getInputRuleKey(),
          expected.getRuleKeys().getInputRuleKey().map(rk -> rk.toString()).orElse(""));
      Assert.assertEquals(result.getRuleType().get(), expected.getBuildRule().getType());
      Assert.assertEquals(
          result.getWallMillisDuration(), expected.getDuration().getWallMillisDuration());
      Assert.assertEquals(result.getBuildRuleStatus().get(), expected.getStatus());
      Assert.assertEquals(result.getCacheResultType().get(), expected.getCacheResult().getType());
      Assert.assertEquals(
          result.getOutputHash(), expected.getOutputHash().map(HashCode::toString).orElse(""));
    }
  }

  @Test
  public void testFileStructure() throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(
            this, "post_build_analysis", tmpPath.getRoot());
    workspace.setUp();

    Config config = Configs.createDefaultConfig(tmpPath.getRoot());
    ProjectFilesystem filesystem =
        TestProjectFilesystems.createProjectFilesystem(tmpPath.getRoot(), config);
    DistBuildPostBuildAnalysis analysis =
        new DistBuildPostBuildAnalysis(
            buildId,
            stampedeId,
            filesystem.resolve(tmpPath.getRoot()),
            ImmutableList.of(buildSlaveRunId1, buildSlaveRunId2),
            DistBuildCommand.class.getSimpleName().toLowerCase());
    Assert.assertEquals(
        analysis.dumpResultsToLogFile(analysis.runAnalysis()),
        tmpPath.getRoot().resolve(BuckConstant.DIST_BUILD_ANALYSIS_FILE_NAME));

    workspace.verify();
  }
}
