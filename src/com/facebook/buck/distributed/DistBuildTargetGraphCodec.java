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
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreCollectors;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Saves and loads the {@link TargetNode}s needed for the build.
 */
public class DistBuildTargetGraphCodec {

  private final ObjectMapper objectMapper;
  private final ParserTargetNodeFactory<TargetNode<?>> parserTargetNodeFactory;
  private final Function<? super TargetNode<?>, ? extends Map<String, Object>> nodeToRawNode;

  public DistBuildTargetGraphCodec(
      ObjectMapper objectMapper,
      ParserTargetNodeFactory<TargetNode<?>> parserTargetNodeFactory,
      Function<? super TargetNode<?>, ? extends Map<String, Object>> nodeToRawNode) {
    this.objectMapper = objectMapper;
    this.parserTargetNodeFactory = parserTargetNodeFactory;
    this.nodeToRawNode = nodeToRawNode;
  }

  public BuildJobStateTargetGraph dump(
      Collection<TargetNode<?>> targetNodes,
      Function<Path, Integer> cellIndexer) {
    BuildJobStateTargetGraph result = new BuildJobStateTargetGraph();

    for (TargetNode<?> targetNode : targetNodes) {
      Map<String, Object> rawTargetNode = nodeToRawNode.apply(targetNode);
      ProjectFilesystem projectFilesystem =
          targetNode.getRuleFactoryParams().getProjectFilesystem();

      BuildJobStateTargetNode remoteNode = new BuildJobStateTargetNode();
      remoteNode.setCellIndex(cellIndexer.apply(projectFilesystem.getRootPath()));
      remoteNode.setBuildTarget(encodeBuildTarget(targetNode.getBuildTarget()));
      try {
        remoteNode.setRawNode(objectMapper.writeValueAsString(rawTargetNode));
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
        buildTarget.getFlavors().stream()
            .map(Object::toString)
            .collect(Collectors.toSet()));
    return remoteTarget;
  }

  public static BuildTarget decodeBuildTarget(BuildJobStateBuildTarget remoteTarget, Cell cell) {

    UnflavoredBuildTarget unflavoredBuildTarget = UnflavoredBuildTarget.builder()
        .setShortName(remoteTarget.getShortName())
        .setBaseName(remoteTarget.getBaseName())
        .setCellPath(cell.getRoot())
        .setCell(Optional.ofNullable(remoteTarget.getCellName()))
        .build();

    ImmutableSet<Flavor> flavors = remoteTarget.flavors.stream()
        .map(ImmutableFlavor::of)
        .collect(MoreCollectors.toImmutableSet());

    return BuildTarget.builder()
        .setUnflavoredBuildTarget(unflavoredBuildTarget)
        .setFlavors(flavors)
        .build();
  }

  public TargetGraph createTargetGraph(
      BuildJobStateTargetGraph remoteTargetGraph,
      Function<Integer, Cell> cellLookup) throws IOException {

    ImmutableMap.Builder<BuildTarget, TargetNode<?>> targetNodeIndexBuilder =
        ImmutableMap.builder();

    for (BuildJobStateTargetNode remoteNode : remoteTargetGraph.getNodes()) {
      Cell cell = cellLookup.apply(remoteNode.getCellIndex());
      ProjectFilesystem projectFilesystem = cell.getFilesystem();
      BuildTarget target = decodeBuildTarget(remoteNode.getBuildTarget(), cell);

      @SuppressWarnings("unchecked")
      Map<String, Object> rawNode = objectMapper.readValue(remoteNode.getRawNode(), Map.class);
      Path buildFilePath = projectFilesystem
          .resolve(target.getBasePath())
          .resolve(cell.getBuildFileName());

      TargetNode<?> targetNode = parserTargetNodeFactory.createTargetNode(
          cell,
          buildFilePath,
          target,
          rawNode,
          input -> SimplePerfEvent.scope(Optional.empty(), input));
      targetNodeIndexBuilder.put(targetNode.getBuildTarget(), targetNode);
    }
    ImmutableMap<BuildTarget, TargetNode<?>> targetNodeIndex = targetNodeIndexBuilder.build();

    MutableDirectedGraph<TargetNode<?>> mutableTargetGraph = new MutableDirectedGraph<>();
    for (TargetNode<?> targetNode : targetNodeIndex.values()) {
      mutableTargetGraph.addNode(targetNode);
      for (BuildTarget dep : targetNode.getDeps()) {
        mutableTargetGraph.addEdge(
            targetNode,
            Preconditions.checkNotNull(targetNodeIndex.get(dep)));
      }
    }

    // TODO(csarbora): make this work with TargetGroups
    return new TargetGraph(mutableTargetGraph, targetNodeIndex, ImmutableSet.of());
  }
}
