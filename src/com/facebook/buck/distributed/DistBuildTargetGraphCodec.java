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

import com.facebook.buck.distributed.thrift.BuildJobStateBuildTarget;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetGraph;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetNode;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Saves and loads the {@link TargetNode}s needed for the build. */
public class DistBuildTargetGraphCodec {

  private final ParserTargetNodeFactory<TargetNode<?, ?>> parserTargetNodeFactory;
  private final Function<? super TargetNode<?, ?>, ? extends Map<String, Object>> nodeToRawNode;
  private Set<String> topLevelTargets;

  public DistBuildTargetGraphCodec(
      ParserTargetNodeFactory<TargetNode<?, ?>> parserTargetNodeFactory,
      Function<? super TargetNode<?, ?>, ? extends Map<String, Object>> nodeToRawNode,
      Set<String> topLevelTargets) {
    this.parserTargetNodeFactory = parserTargetNodeFactory;
    this.nodeToRawNode = nodeToRawNode;
    this.topLevelTargets = topLevelTargets;
  }

  public BuildJobStateTargetGraph dump(
      Collection<TargetNode<?, ?>> targetNodes, DistBuildCellIndexer cellIndexer) {
    BuildJobStateTargetGraph result = new BuildJobStateTargetGraph();

    for (TargetNode<?, ?> targetNode : targetNodes) {
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
      result.addToNodes(remoteNode);
    }

    return result;
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
        UnflavoredBuildTarget.builder()
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
            .collect(MoreCollectors.toImmutableSet());

    return BuildTarget.builder()
        .setUnflavoredBuildTarget(unflavoredBuildTarget)
        .setFlavors(flavors)
        .build();
  }

  public TargetGraphAndBuildTargets createTargetGraph(
      BuildJobStateTargetGraph remoteTargetGraph, Function<Integer, Cell> cellLookup)
      throws IOException {

    ImmutableMap.Builder<BuildTarget, TargetNode<?, ?>> targetNodeIndexBuilder =
        ImmutableMap.builder();

    ImmutableSet.Builder<BuildTarget> buildTargetsBuilder = ImmutableSet.builder();

    for (BuildJobStateTargetNode remoteNode : remoteTargetGraph.getNodes()) {
      Cell cell = cellLookup.apply(remoteNode.getCellIndex());
      ProjectFilesystem projectFilesystem = cell.getFilesystem();
      BuildTarget target = decodeBuildTarget(remoteNode.getBuildTarget(), cell);
      if (topLevelTargets.contains(target.getFullyQualifiedName())) {
        buildTargetsBuilder.add(target);
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> rawNode = ObjectMappers.readValue(remoteNode.getRawNode(), Map.class);
      Path buildFilePath =
          projectFilesystem.resolve(target.getBasePath()).resolve(cell.getBuildFileName());

      TargetNode<?, ?> targetNode =
          parserTargetNodeFactory.createTargetNode(
              cell,
              buildFilePath,
              target,
              rawNode,
              input -> SimplePerfEvent.scope(Optional.empty(), input));
      targetNodeIndexBuilder.put(targetNode.getBuildTarget(), targetNode);
    }

    ImmutableSet<BuildTarget> buildTargets = buildTargetsBuilder.build();
    Preconditions.checkArgument(topLevelTargets.size() == buildTargets.size());

    ImmutableMap<BuildTarget, TargetNode<?, ?>> targetNodeIndex = targetNodeIndexBuilder.build();

    MutableDirectedGraph<TargetNode<?, ?>> mutableTargetGraph = new MutableDirectedGraph<>();
    for (TargetNode<?, ?> targetNode : targetNodeIndex.values()) {
      mutableTargetGraph.addNode(targetNode);
      for (BuildTarget dep : targetNode.getParseDeps()) {
        mutableTargetGraph.addEdge(
            targetNode, Preconditions.checkNotNull(targetNodeIndex.get(dep)));
      }
    }

    TargetGraph targetGraph = new TargetGraph(mutableTargetGraph, targetNodeIndex);

    return TargetGraphAndBuildTargets.builder()
        .setTargetGraph(targetGraph)
        .addAllBuildTargets(buildTargets)
        .build();
  }
}
