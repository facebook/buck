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
package com.facebook.buck.cli;

import static com.facebook.buck.distributed.ClientStatsTracker.DistBuildClientStat.LOCAL_FILE_HASH_COMPUTATION;

import com.facebook.buck.cli.BuildCommand.GraphsAndBuildTargets;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.ClientStatsTracker;
import com.facebook.buck.distributed.DistBuildCellIndexer;
import com.facebook.buck.distributed.DistBuildFileHashes;
import com.facebook.buck.distributed.DistBuildState;
import com.facebook.buck.distributed.DistBuildTargetGraphCodec;
import com.facebook.buck.distributed.thrift.BuildJobState;
import com.facebook.buck.distributed.thrift.RemoteCommand;
import com.facebook.buck.parser.DefaultParserTargetNodeFactory;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.rules.coercer.DefaultConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.rules.coercer.PathTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class AsyncJobStateFactory {

  private static final Logger LOG = Logger.get(AsyncJobStateFactory.class);

  static AsyncJobStateAndCells computeDistBuildState(
      CommandRunnerParams params,
      GraphsAndBuildTargets graphsAndBuildTargets,
      WeightedListeningExecutorService executorService,
      Optional<ClientStatsTracker> clientStatsTracker,
      RemoteCommand remoteCommand) {
    DistBuildCellIndexer cellIndexer = new DistBuildCellIndexer(params.getCell());

    // Compute the file hashes.
    ActionGraphAndBuilder actionGraphAndBuilder =
        graphsAndBuildTargets.getGraphs().getActionGraphAndBuilder();
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(actionGraphAndBuilder.getActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    clientStatsTracker.ifPresent(tracker -> tracker.startTimer(LOCAL_FILE_HASH_COMPUTATION));
    DistBuildFileHashes distributedBuildFileHashes =
        new DistBuildFileHashes(
            actionGraphAndBuilder.getActionGraph(),
            pathResolver,
            ruleFinder,
            params.getFileHashCache(),
            cellIndexer,
            executorService,
            params.getRuleKeyConfiguration(),
            params.getCell());
    distributedBuildFileHashes
        .getFileHashesComputationFuture()
        .addListener(
            () ->
                clientStatsTracker.ifPresent(
                    tracker -> tracker.stopTimer(LOCAL_FILE_HASH_COMPUTATION)),
            executorService);

    // Distributed builds serialize and send the unversioned target graph,
    // and then deserialize and version remotely.
    TargetGraphAndBuildTargets targetGraphAndBuildTargets =
        graphsAndBuildTargets.getGraphs().getTargetGraphForDistributedBuild();

    TypeCoercerFactory typeCoercerFactory =
        new DefaultTypeCoercerFactory(PathTypeCoercer.PathExistenceVerificationMode.DO_NOT_VERIFY);
    ParserTargetNodeFactory<Map<String, Object>> parserTargetNodeFactory =
        DefaultParserTargetNodeFactory.createForDistributedBuild(
            params.getKnownRuleTypesProvider(),
            new DefaultConstructorArgMarshaller(typeCoercerFactory),
            new TargetNodeFactory(
                typeCoercerFactory, PathTypeCoercer.PathExistenceVerificationMode.DO_NOT_VERIFY));
    DistBuildTargetGraphCodec targetGraphCodec =
        new DistBuildTargetGraphCodec(
            executorService,
            parserTargetNodeFactory,
            input -> {
              return params
                  .getParser()
                  .getTargetNodeRawAttributes(
                      params.getCell().getCell(input.getBuildTarget()), executorService, input);
            },
            targetGraphAndBuildTargets
                .getBuildTargets()
                .stream()
                .map(t -> t.getFullyQualifiedName())
                .collect(Collectors.toSet()));

    ListenableFuture<BuildJobState> asyncJobState =
        executorService.submit(
            () -> {
              try {
                BuildJobState state =
                    DistBuildState.dump(
                        cellIndexer,
                        distributedBuildFileHashes,
                        targetGraphCodec,
                        targetGraphAndBuildTargets.getTargetGraph(),
                        graphsAndBuildTargets.getBuildTargets(),
                        remoteCommand,
                        clientStatsTracker);
                LOG.info("Finished computing serializable distributed build state.");
                return state;
              } catch (InterruptedException ex) {
                distributedBuildFileHashes.cancel();
                LOG.warn(
                    ex,
                    "Failed computing serializable distributed build state as interrupted. Local build probably finished first.");
                Thread.currentThread().interrupt();
                throw ex;
              }
            });

    Futures.addCallback(
        asyncJobState,
        new FutureCallback<BuildJobState>() {
          @Override
          public void onSuccess(@Nullable BuildJobState result) {
            LOG.info("Finished creating stampede BuildJobState.");
          }

          @Override
          public void onFailure(Throwable t) {
            // We need to cancel file hash computation here as well, in case the asyncJobState
            // future didn't start at all, and hence wasn't able to cancel file hash computation
            // itself.
            LOG.warn("Failed to create stampede BuildJobState. Cancelling file hash computation.");
            distributedBuildFileHashes.cancel();
          }
        },
        MoreExecutors.directExecutor());

    return AsyncJobStateAndCells.of(asyncJobState, cellIndexer);
  }
}
