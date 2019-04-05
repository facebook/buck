/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.cli.HeapDumper.DumpType;
import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.remoteexecution.WorkerRequirementsProvider;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.builders.ModernBuildRuleRemoteExecutionHelper;
import com.facebook.buck.rules.modern.builders.RemoteExecutionActionInfo;
import com.facebook.buck.util.CommandLineException;
import com.facebook.buck.util.concurrent.JobLimiter;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/**
 * Tests performance of preparing MBR rules for remote execution (primarily merkle tree node
 * computations).
 */
public class PerfMbrPrepareRemoteExecutionCommand
    extends AbstractPerfCommand<PerfMbrPrepareRemoteExecutionCommand.PreparedState> {
  @Argument private List<String> arguments = new ArrayList<>();

  @Nullable
  @Option(
      name = "--heap-dump",
      usage = "path to write a heap dump after the first iteration. The path must end in .hprof.")
  private String heapDumpPath = null;

  @Override
  protected String getComputationName() {
    return "creating mbr merkle trees";
  }

  @Override
  PreparedState prepareTest(CommandRunnerParams params) throws Exception {
    if (heapDumpPath != null) {
      Verify.verify(
          heapDumpPath.endsWith(".hprof"),
          "--heap-dump path must end in .hprof, got %s",
          heapDumpPath);
      HeapDumper.init();
    }
    // Create a TargetGraph that is composed of the transitive closure of all of the dependent
    // BuildRules for the specified BuildTargetPaths.
    ImmutableSet<BuildTarget> targets = convertArgumentsToBuildTargets(params, arguments);

    if (targets.isEmpty()) {
      throw new CommandLineException("must specify at least one build target");
    }

    TargetGraph targetGraph = getTargetGraph(params, targets);

    // Get a fresh action graph since we might unsafely run init from disks...
    // Also, we don't measure speed of this part.
    ActionGraphBuilder graphBuilder =
        params.getActionGraphProvider().getFreshActionGraph(targetGraph).getActionGraphBuilder();

    ImmutableList<BuildRule> rulesInGraph = getRulesInGraph(graphBuilder, targets);
    return new PreparedState(rulesInGraph, graphBuilder);
  }

  @Override
  void runPerfTest(CommandRunnerParams params, PreparedState state) throws Exception {
    Cell rootCell = params.getCell();
    Protocol protocol = new GrpcProtocol();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(state.graphBuilder);
    ImmutableSet<Optional<String>> cellNames =
        ModernBuildRuleRemoteExecutionHelper.getCellNames(rootCell);
    ModernBuildRuleRemoteExecutionHelper helper =
        new ModernBuildRuleRemoteExecutionHelper(
            params.getBuckEventBus(),
            protocol,
            ruleFinder,
            rootCell.getCellPathResolver(),
            rootCell,
            cellNames,
            ModernBuildRuleRemoteExecutionHelper.getCellPathPrefix(
                rootCell.getCellPathResolver(), cellNames),
            path -> HashCode.fromInt(path.hashCode()));

    // Use a service similar to a build. This helps with contention issues and really helps detect
    // GC issues (when using just 1 thread, the gc has a ton of resources to keep up with our
    // allocations).
    ListeningExecutorService service =
        MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(
                params.getBuckConfig().getView(BuildBuckConfig.class).getNumThreads()));

    RemoteExecutionConfig config = params.getBuckConfig().getView(RemoteExecutionConfig.class);

    int maxPendingUploads = config.getStrategyConfig().getMaxConcurrentPendingUploads();
    JobLimiter uploadsLimiter = new JobLimiter(maxPendingUploads);

    LinkedList<Pair<RemoteExecutionActionInfo, SettableFuture<?>>> pendingInfos =
        new LinkedList<>();
    BlockingQueue<Pair<RemoteExecutionActionInfo, SettableFuture<?>>> infos =
        new LinkedBlockingQueue<>();
    int count = 0;

    Set<Digest> containedHashes = Sets.newConcurrentHashSet();

    for (BuildRule buildRule : state.rulesInGraph) {
      if (buildRule instanceof ModernBuildRule) {
        count++;
        uploadsLimiter.schedule(
            service,
            () ->
                Futures.transformAsync(
                    service.submit(
                        () -> {
                          try {
                            return helper.prepareRemoteExecution(
                                (ModernBuildRule<?>) buildRule,
                                digest -> !containedHashes.contains(digest),
                                WorkerRequirementsProvider.DEFAULT);
                          } catch (Exception e) {
                            // Ignore. Hopefully this is just a serialization failure. In normal
                            // builds, those rules just fall back to non-RE.
                            return null;
                          }
                        }),
                    info -> {
                      SettableFuture<?> future = SettableFuture.create();
                      infos.add(new Pair<>(info, future));
                      return future;
                    }));
      }
    }

    // Sort of simulate the way that we upload things as we get to them. There's optimizations in
    // the helper that depend on this.
    int finished = 0;
    int wantedPendingUploads = maxPendingUploads / 2;
    while (finished < count) {
      Pair<RemoteExecutionActionInfo, SettableFuture<?>> info = infos.take();
      finished++;
      pendingInfos.add(info);
      if (pendingInfos.size() > wantedPendingUploads) {
        Pair<RemoteExecutionActionInfo, SettableFuture<?>> infoToFinish =
            pendingInfos.removeFirst();
        if (infoToFinish.getFirst() != null) {
          infoToFinish
              .getFirst()
              .getRequiredData()
              .forEach(data -> containedHashes.add(data.getDigest()));
        }
        infoToFinish.getSecond().set(null);
      }
    }

    // Do this before clearing out the remaining Futures so it reflects some work still in progress.
    if (heapDumpPath != null && !state.heapDumped) {
      printWarning(params, "Dumping heap to %s.", heapDumpPath);
      HeapDumper.dumpHeap(heapDumpPath, DumpType.REACHABLE_OBJECTS_ONLY);
      state.heapDumped = true;
    }

    for (Pair<RemoteExecutionActionInfo, SettableFuture<?>> finishedInfo : pendingInfos) {
      finishedInfo.getSecond().set(null);
    }
  }

  @Override
  public String getShortDescription() {
    return "test performance of preparing mbr rules for remote execution";
  }

  /** Preapared state with the action graph created. */
  static class PreparedState {
    private final ImmutableList<BuildRule> rulesInGraph;
    private final BuildRuleResolver graphBuilder;
    private boolean heapDumped = false;

    public PreparedState(ImmutableList<BuildRule> rulesInGraph, BuildRuleResolver graphBuilder) {
      this.rulesInGraph = rulesInGraph;
      this.graphBuilder = graphBuilder;
    }
  }
}
