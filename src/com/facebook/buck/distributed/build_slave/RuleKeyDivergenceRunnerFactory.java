/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.distributed.build_slave;

import com.facebook.buck.core.build.engine.impl.DefaultRuleDepsCache;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.calculator.ParallelRuleKeyCalculator;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildState;
import com.facebook.buck.distributed.RuleKeyUtils;
import com.facebook.buck.distributed.build_slave.HeartbeatService.HeartbeatCallback;
import com.facebook.buck.distributed.thrift.BuildSlaveEvent;
import com.facebook.buck.distributed.thrift.BuildSlaveEventType;
import com.facebook.buck.distributed.thrift.BuildSlaveRunId;
import com.facebook.buck.distributed.thrift.BuildStatus;
import com.facebook.buck.distributed.thrift.RuleKeyCalculatedEvent;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyCacheScope;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.util.ExitCode;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.Lists;
import com.google.common.io.Closer;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * * Contains all logic for creating a DistBuildModeRunner to be used for rule key divergence checks
 */
public class RuleKeyDivergenceRunnerFactory {
  private static final Logger LOG = Logger.get(RuleKeyDivergenceRunnerFactory.class);

  private static final int RULE_CALCULATION_EVENTS_PER_FRONTEND_REQUEST = 1000;

  /** Creates DistBuildModeRunner to be used for rule key divergence checks */
  public static DistBuildModeRunner createRunner(
      StampedeId stampedeId,
      BuildSlaveRunId buildSlaveRunId,
      Clock clock,
      DistBuildService distBuildService,
      DelegateAndGraphsInitializer initializer,
      RuleKeyConfiguration ruleKeyConfiguration,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope,
      WeightedListeningExecutorService executorService,
      BuckEventBus eventBus,
      DistBuildState state,
      Cell rootCell) {
    return new AbstractDistBuildModeRunner() {
      @Override
      public ListenableFuture<?> getAsyncPrepFuture() {
        return Futures.immediateFuture(null);
      }

      @Override
      public ExitCode runAndReturnExitCode(HeartbeatService heartbeatService)
          throws IOException, InterruptedException {

        try (Closer closer = Closer.create()) {
          closer.register(
              heartbeatService.addCallback(
                  "ReportCoordinatorAlive", createHeartbeatCallback(stampedeId, distBuildService)));

          try {
            List<Pair<BuildRule, RuleKey>> rulesAndKeys =
                calculateDefaultRuleKeys(
                    getTopLevelTargetsToBuild(state, rootCell),
                    initializer,
                    ruleKeyConfiguration,
                    ruleKeyCacheScope,
                    executorService,
                    eventBus);

            List<BuildSlaveEvent> ruleKeyCalculatedEvents =
                rulesAndKeys
                    .stream()
                    .map(
                        rk -> {
                          RuleKeyCalculatedEvent event = new RuleKeyCalculatedEvent();
                          event.setBuildTarget(rk.getFirst().getFullyQualifiedName());
                          event.setDefaultRuleKey(rk.getSecond().getHashCode().toString());

                          BuildSlaveEvent buildSlaveEvent = new BuildSlaveEvent();
                          buildSlaveEvent.setEventType(
                              BuildSlaveEventType.RULE_KEY_CALCULATED_EVENT);
                          buildSlaveEvent.setRuleKeyCalculatedEvent(event);

                          return buildSlaveEvent;
                        })
                    .collect(Collectors.toList());

            List<List<BuildSlaveEvent>> ruleKeyCalculationBatches =
                Lists.partition(
                    ruleKeyCalculatedEvents, RULE_CALCULATION_EVENTS_PER_FRONTEND_REQUEST);

            for (List<BuildSlaveEvent> ruleKeyCalculateBatch : ruleKeyCalculationBatches) {
              distBuildService.uploadBuildSlaveEvents(
                  stampedeId, buildSlaveRunId, ruleKeyCalculateBatch);
            }

            // Ensure client doesn't wait for timeout before completing
            distBuildService.sendAllBuildRulesPublishedEvent(
                stampedeId, buildSlaveRunId, clock.currentTimeMillis());
            distBuildService.setFinalBuildStatus(
                stampedeId,
                BuildStatus.FINISHED_SUCCESSFULLY,
                "Rule key divergence check complete");
            return ExitCode.SUCCESS;

          } catch (ExecutionException | IOException e) {
            LOG.error(e, "Failed to calculate rule keys");
            distBuildService.setFinalBuildStatus(
                stampedeId, BuildStatus.FAILED, "Could not compute or publish rule keys");
            return ExitCode.FATAL_GENERIC;
          }
        }
      }
    };
  }

  private static HeartbeatCallback createHeartbeatCallback(
      StampedeId stampedeId, DistBuildService distBuildService) {
    return () -> distBuildService.reportCoordinatorIsAlive(stampedeId);
  }

  private static List<Pair<BuildRule, RuleKey>> calculateDefaultRuleKeys(
      Iterable<BuildTarget> topLevelTargets,
      DelegateAndGraphsInitializer initializer,
      RuleKeyConfiguration ruleKeyConfiguration,
      RuleKeyCacheScope<RuleKey> ruleKeyCacheScope,
      WeightedListeningExecutorService executorService,
      BuckEventBus eventBus)
      throws ExecutionException, InterruptedException {
    DelegateAndGraphs graphs = initializer.getDelegateAndGraphs().get();

    ActionGraphBuilder actionGraphBuilder =
        graphs.getActionGraphAndBuilder().getActionGraphBuilder();

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(actionGraphBuilder);

    ParallelRuleKeyCalculator<RuleKey> ruleKeyCalculator =
        new ParallelRuleKeyCalculator<>(
            executorService,
            new DefaultRuleKeyFactory(
                new RuleKeyFieldLoader(ruleKeyConfiguration),
                graphs.getCachingBuildEngineDelegate().getFileHashCache(),
                DefaultSourcePathResolver.from(ruleFinder),
                ruleFinder,
                ruleKeyCacheScope.getCache(),
                Optional.empty()),
            new DefaultRuleDepsCache(actionGraphBuilder),
            (buckEventBus, rule) -> () -> {});

    return RuleKeyUtils.calculateDefaultRuleKeys(
            actionGraphBuilder, ruleKeyCalculator, eventBus, topLevelTargets)
        .get();
  }

  private static List<BuildTarget> getTopLevelTargetsToBuild(DistBuildState state, Cell rootCell) {
    return state
        .getRemoteState()
        .getTopLevelTargets()
        .stream()
        .map(
            target ->
                BuildTargetParser.fullyQualifiedNameToBuildTarget(
                    rootCell.getCellPathResolver(), target))
        .collect(Collectors.toList());
  }
}
