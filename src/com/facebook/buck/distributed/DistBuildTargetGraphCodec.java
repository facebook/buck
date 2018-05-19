/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.knowntypes.KnownBuildRuleTypesProvider;
import com.facebook.buck.distributed.thrift.BuildJobStateBuildTarget;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetGraph;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetNode;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.ImmutableBuildTarget;
import com.facebook.buck.model.ImmutableUnflavoredBuildTarget;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.util.MoreMaps;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Saves and loads the {@link TargetNode}s needed for the build. */
public class DistBuildTargetGraphCodec {
  private static final Logger LOG = Logger.get(DistBuildTargetGraphCodec.class);

  private ListeningExecutorService cpuExecutor;
  private final ParserTargetNodeFactory<Map<String, Object>> parserTargetNodeFactory;
  private final Function<? super TargetNode<?, ?>, ? extends Map<String, Object>> nodeToRawNode;
  private Set<String> topLevelTargets;

  public DistBuildTargetGraphCodec(
      ListeningExecutorService cpuExecutor,
      ParserTargetNodeFactory<Map<String, Object>> parserTargetNodeFactory,
      Function<? super TargetNode<?, ?>, ? extends Map<String, Object>> nodeToRawNode,
      Set<String> topLevelTargets) {
    this.cpuExecutor = cpuExecutor;
    this.parserTargetNodeFactory = parserTargetNodeFactory;
    this.nodeToRawNode = nodeToRawNode;
    this.topLevelTargets = topLevelTargets;
  }

  public BuildJobStateTargetGraph dump(
      Collection<TargetNode<?, ?>> targetNodes, DistBuildCellIndexer cellIndexer)
      throws InterruptedException {
    List<ListenableFuture<BuildJobStateTargetNode>> targetNodeFutures = new LinkedList<>();

    for (TargetNode<?, ?> targetNode : targetNodes) {
      targetNodeFutures.add(asyncSerializeTargetNode(cellIndexer, targetNode));
    }

    List<BuildJobStateTargetNode> serializedTargetNodes = null;
    try {
      serializedTargetNodes = Futures.allAsList(targetNodeFutures).get();
    } catch (ExecutionException e) {
      LOG.error(e, "Failed to serialize target graph into BuildJobStateTargetGraph");
      throw new RuntimeException(e);
    }

    BuildJobStateTargetGraph result = new BuildJobStateTargetGraph();
    result.setNodes(serializedTargetNodes);

    return result;
  }

  private ListenableFuture<BuildJobStateTargetNode> asyncSerializeTargetNode(
      DistBuildCellIndexer cellIndexer, TargetNode<?, ?> targetNode) {
    return cpuExecutor.submit(
        () -> {
          Map<String, Object> rawTargetNode = nodeToRawNode.apply(targetNode);
          ProjectFilesystem projectFilesystem = targetNode.getFilesystem();

          BuildJobStateTargetNode remoteNode = new BuildJobStateTargetNode();
          remoteNode.setCellIndex(cellIndexer.getCellIndex(projectFilesystem.getRootPath()));
          remoteNode.setBuildTarget(encodeBuildTarget(targetNode.getBuildTarget()));
          try {
            remoteNode.setRawNode(ObjectMappers.WRITER.writeValueAsString(rawTargetNode));
          } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
          }
          return remoteNode;
        });
  }

  public static BuildJobStateBuildTarget encodeBuildTarget(BuildTarget buildTarget) {
    BuildJobStateBuildTarget remoteTarget = new BuildJobStateBuildTarget();
    remoteTarget.setShortName(buildTarget.getShortName());
    remoteTarget.setBaseName(buildTarget.getBaseName());
    if (buildTarget.getCell().isPresent()) {
      remoteTarget.setCellName(buildTarget.getCell().get());
    }
    remoteTarget.setFlavors(
        buildTarget.getFlavors().stream().map(Object::toString).collect(Collectors.toSet()));
    return remoteTarget;
  }

  public static BuildTarget decodeBuildTarget(BuildJobStateBuildTarget remoteTarget, Cell cell) {

    UnflavoredBuildTarget unflavoredBuildTarget =
        ImmutableUnflavoredBuildTarget.builder()
            .setShortName(remoteTarget.getShortName())
            .setBaseName(remoteTarget.getBaseName())
            .setCellPath(cell.getRoot())
            .setCell(Optional.ofNullable(remoteTarget.getCellName()))
            .build();

    ImmutableSet<Flavor> flavors =
        remoteTarget
            .flavors
            .stream()
            .map(InternalFlavor::of)
            .collect(ImmutableSet.toImmutableSet());

    return ImmutableBuildTarget.of(unflavoredBuildTarget, flavors);
  }

  public TargetGraphAndBuildTargets createTargetGraph(
      BuildJobStateTargetGraph remoteTargetGraph,
      Function<Integer, Cell> cellLookup,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider)
      throws InterruptedException {

    ConcurrentMap<BuildTarget, TargetNode<?, ?>> index = new ConcurrentHashMap<>();
    ConcurrentMap<BuildTarget, TargetNode<?, ?>> graphNodes = new ConcurrentHashMap<>();
    ConcurrentMap<BuildTarget, Boolean> buildTargets = new ConcurrentHashMap<>();

    List<ListenableFuture<Void>> processRemoteBuildTargetFutures = new LinkedList<>();

    for (BuildJobStateTargetNode remoteNode : remoteTargetGraph.getNodes()) {
      processRemoteBuildTargetFutures.add(
          asyncProcessRemoteBuildTarget(
              cellLookup,
              knownBuildRuleTypesProvider,
              index,
              graphNodes,
              buildTargets,
              remoteNode));
    }

    try {
      Futures.allAsList(processRemoteBuildTargetFutures).get();
    } catch (ExecutionException e) {
      LOG.error(e, "Failed to deserialize target graph nodes");
      throw new RuntimeException(e);
    }

    Preconditions.checkArgument(topLevelTargets.size() == buildTargets.size());

    ImmutableMap<BuildTarget, TargetNode<?, ?>> targetNodeIndex = ImmutableMap.copyOf(index);

    MutableDirectedGraph<TargetNode<?, ?>> mutableTargetGraph = new MutableDirectedGraph<>();
    for (TargetNode<?, ?> targetNode : graphNodes.values()) {
      mutableTargetGraph.addNode(targetNode);
      for (BuildTarget dep : targetNode.getParseDeps()) {
        mutableTargetGraph.addEdge(
            targetNode,
            Preconditions.checkNotNull(
                graphNodes.get(dep),
                "Dependency [%s] of target [%s] was not found in the client-side target graph.",
                dep.getFullyQualifiedName(),
                targetNode.getBuildTarget().getFullyQualifiedName()));
      }
    }

    TargetGraph targetGraph = new TargetGraph(mutableTargetGraph, targetNodeIndex);

    return TargetGraphAndBuildTargets.builder()
        .setTargetGraph(targetGraph)
        .addAllBuildTargets(buildTargets.keySet())
        .build();
  }

  private ListenableFuture<Void> asyncProcessRemoteBuildTarget(
      Function<Integer, Cell> cellLookup,
      KnownBuildRuleTypesProvider knownBuildRuleTypesProvider,
      ConcurrentMap<BuildTarget, TargetNode<?, ?>> index,
      ConcurrentMap<BuildTarget, TargetNode<?, ?>> graphNodes,
      ConcurrentMap<BuildTarget, Boolean> buildTargets,
      BuildJobStateTargetNode remoteNode) {
    return cpuExecutor.submit(
        () -> {
          Cell cell = cellLookup.apply(remoteNode.getCellIndex());
          if (remoteNode.getCellIndex() == DistBuildCellIndexer.ROOT_CELL_INDEX) {
            cell = cell.withCanonicalName(Optional.empty());
          }

          ProjectFilesystem projectFilesystem = cell.getFilesystem();
          BuildTarget target = decodeBuildTarget(remoteNode.getBuildTarget(), cell);
          if (topLevelTargets.contains(target.getFullyQualifiedName())) {
            buildTargets.put(target, true);
          }

          Map<String, Object> rawNode = getRawNode(remoteNode);

          Path buildFilePath =
              projectFilesystem.resolve(target.getBasePath()).resolve(cell.getBuildFileName());

          TargetNode<?, ?> targetNode =
              parserTargetNodeFactory.createTargetNode(
                  cell,
                  knownBuildRuleTypesProvider.get(cell),
                  buildFilePath,
                  target,
                  rawNode,
                  input -> SimplePerfEvent.scope(Optional.empty(), input));

          MoreMaps.putIfAbsentCheckEquals(index, target, targetNode);
          MoreMaps.putIfAbsentCheckEquals(graphNodes, target, targetNode);

          if (target.isFlavored()) {
            BuildTarget unflavoredTarget =
                ImmutableBuildTarget.of(target.getUnflavoredBuildTarget());
            TargetNode<?, ?> unflavoredTargetNode =
                parserTargetNodeFactory.createTargetNode(
                    cell,
                    knownBuildRuleTypesProvider.get(cell),
                    buildFilePath,
                    unflavoredTarget,
                    rawNode,
                    input -> SimplePerfEvent.scope(Optional.empty(), input));

            MoreMaps.putCheckEquals(index, unflavoredTarget, unflavoredTargetNode);
          }

          return null;
        });
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getRawNode(BuildJobStateTargetNode remoteNode) {
    try {
      return ObjectMappers.readValue(remoteNode.getRawNode(), Map.class);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
