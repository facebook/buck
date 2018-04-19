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

package com.facebook.buck.distributed.build_client;

import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.CREATE_DISTRIBUTED_BUILD;
import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_PREPARATION;

import com.facebook.buck.command.BuildExecutorArgs;
import com.facebook.buck.distributed.ArtifactCacheByBuildRule;
import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildArtifactCacheImpl;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.DistBuildConfig;
import com.facebook.buck.distributed.DistBuildCreatedEvent;
import com.facebook.buck.distributed.DistBuildService;
import com.facebook.buck.distributed.DistBuildService.DistBuildRejectedException;
import com.facebook.buck.distributed.build_slave.CacheOptimizedBuildTargetsQueueFactory;
import com.facebook.buck.distributed.thrift.BuckVersion;
import com.facebook.buck.distributed.thrift.BuildJob;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.BuildMode;
import com.facebook.buck.distributed.thrift.MinionRequirements;
import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.ActionAndTargetGraphs;
import com.facebook.buck.rules.ParallelRuleKeyCalculator;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Phase before the build. */
public class PreBuildPhase {
  private static final Logger LOG = Logger.get(PreBuildPhase.class);

  private final DistBuildService distBuildService;
  private final ClientStatsTracker distBuildClientStats;
  private final ListenableFuture<BuildJobState> asyncJobState;
  private final DistBuildCellIndexer distBuildCellIndexer;
  private final BuckVersion buckVersion;
  private final BuildExecutorArgs buildExecutorArgs;
  private final ImmutableSet<BuildTarget> topLevelTargets;
  private final ActionAndTargetGraphs actionAndTargetGraphs;
  private final String buildLabel;

  public PreBuildPhase(
      DistBuildService distBuildService,
      ClientStatsTracker distBuildClientStats,
      ListenableFuture<BuildJobState> asyncJobState,
      DistBuildCellIndexer distBuildCellIndexer,
      BuckVersion buckVersion,
      BuildExecutorArgs buildExecutorArgs,
      ImmutableSet<BuildTarget> topLevelTargets,
      ActionAndTargetGraphs buildGraphs,
      String buildLabel) {
    this.distBuildService = distBuildService;
    this.distBuildClientStats = distBuildClientStats;
    this.asyncJobState = asyncJobState;
    this.distBuildCellIndexer = distBuildCellIndexer;
    this.buckVersion = buckVersion;
    this.buildExecutorArgs = buildExecutorArgs;
    this.topLevelTargets = topLevelTargets;
    this.actionAndTargetGraphs = buildGraphs;
    this.buildLabel = buildLabel;
  }

  /** Run all steps required before the build. */
  public Pair<StampedeId, ListenableFuture<Void>> runPreDistBuildLocalStepsAsync(
      ListeningExecutorService networkExecutorService,
      ProjectFilesystem projectFilesystem,
      FileHashCache fileHashCache,
      BuckEventBus eventBus,
      BuildId buildId,
      BuildMode buildMode,
      MinionRequirements minionRequirements,
      String repository,
      String tenantId,
      ListenableFuture<ParallelRuleKeyCalculator<RuleKey>> localRuleKeyCalculatorFuture)
      throws IOException, DistBuildRejectedException {
    ConsoleEventsDispatcher consoleEventsDispatcher = new ConsoleEventsDispatcher(eventBus);

    distBuildClientStats.startTimer(CREATE_DISTRIBUTED_BUILD);
    List<String> buildTargets =
        topLevelTargets
            .stream()
            .map(x -> x.getFullyQualifiedName())
            .sorted()
            .collect(Collectors.toList());
    BuildJob job =
        distBuildService.createBuild(
            buildId, buildMode, minionRequirements, repository, tenantId, buildTargets, buildLabel);
    distBuildClientStats.stopTimer(CREATE_DISTRIBUTED_BUILD);

    StampedeId stampedeId = job.getStampedeId();
    eventBus.post(new DistBuildCreatedEvent(stampedeId));

    LOG.info("Created job. StampedeId = " + stampedeId.getId());

    consoleEventsDispatcher.postDistBuildStatusEvent(
        job, ImmutableList.of(), "SERIALIZING AND UPLOADING DATA");

    List<ListenableFuture<?>> asyncJobs = new LinkedList<>();

    asyncJobs.add(
        Futures.transform(
            asyncJobState,
            jobState -> {
              LOG.info("Uploading local changes.");
              return distBuildService.uploadMissingFilesAsync(
                  distBuildCellIndexer.getLocalFilesystemsByCellIndex(),
                  jobState.fileHashes,
                  distBuildClientStats,
                  networkExecutorService);
            },
            networkExecutorService));

    asyncJobs.add(
        Futures.transform(
            asyncJobState,
            jobState -> {
              LOG.info("Uploading target graph.");
              try {
                distBuildService.uploadTargetGraph(jobState, stampedeId, distBuildClientStats);
              } catch (IOException e) {
                throw new RuntimeException("Failed to upload target graph with exception.", e);
              }
              return null;
            },
            networkExecutorService));

    LOG.info("Uploading buck dot-files.");
    asyncJobs.add(
        distBuildService.uploadBuckDotFilesAsync(
            stampedeId,
            projectFilesystem,
            fileHashCache,
            distBuildClientStats,
            networkExecutorService));

    asyncJobs.add(
        networkExecutorService.submit(
            () -> {
              LOG.info("Setting buck version.");
              try {
                distBuildService.setBuckVersion(stampedeId, buckVersion, distBuildClientStats);
              } catch (IOException e) {
                throw new RuntimeException("Failed to set buck-version with exception.", e);
              }
            }));

    DistBuildConfig distBuildConfig = new DistBuildConfig(buildExecutorArgs.getBuckConfig());

    if (distBuildConfig.isUploadFromLocalCacheEnabled()) {
      asyncJobs.add(
          Futures.transformAsync(
              localRuleKeyCalculatorFuture,
              localRuleKeyCalculator -> {
                try (ArtifactCacheByBuildRule artifactCache =
                    new DistBuildArtifactCacheImpl(
                        actionAndTargetGraphs.getActionGraphAndResolver().getResolver(),
                        networkExecutorService,
                        buildExecutorArgs.getArtifactCacheFactory().remoteOnlyInstance(true, false),
                        eventBus,
                        localRuleKeyCalculator,
                        Optional.of(
                            buildExecutorArgs
                                .getArtifactCacheFactory()
                                .localOnlyInstance(true, false)))) {

                  return new CacheOptimizedBuildTargetsQueueFactory(
                          actionAndTargetGraphs.getActionGraphAndResolver().getResolver(),
                          artifactCache,
                          /* isDeepRemoteBuild */ false,
                          localRuleKeyCalculator.getRuleDepsCache())
                      .uploadCriticalNodesFromLocalCache(topLevelTargets, distBuildClientStats);

                } catch (Exception e) {
                  LOG.error(e, "Failed to create BuildTargetsQueue.");
                  throw new RuntimeException(e);
                }
              },
              networkExecutorService));
    }

    ListenableFuture<Void> asyncPrep =
        Futures.transform(
            Futures.allAsList(asyncJobs),
            results -> {
              LOG.info("Finished async preparation of stampede job.");
              consoleEventsDispatcher.postDistBuildStatusEvent(
                  job, ImmutableList.of(), "STARTING REMOTE BUILD");

              // Everything is now setup remotely to run the distributed build. No more local prep.
              this.distBuildClientStats.stopTimer(LOCAL_PREPARATION);
              return null;
            },
            MoreExecutors.directExecutor());

    return new Pair<StampedeId, ListenableFuture<Void>>(stampedeId, asyncPrep);
  }
}
