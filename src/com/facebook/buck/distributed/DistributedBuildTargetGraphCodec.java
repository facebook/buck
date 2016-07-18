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
import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.parser.ParserTargetNodeFactory;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * Saves and loads the {@link TargetNode}s needed for the build.
 */
public class DistributedBuildTargetGraphCodec {

  private final ProjectFilesystem rootFilesystem;
  private final Cell rootCell;
  private final ObjectMapper objectMapper;
  private final ParserTargetNodeFactory parserTargetNodeFactory;
  private final Function<? super TargetNode<?>, ? extends Map<String, Object>> nodeToRawNode;

  public DistributedBuildTargetGraphCodec(
      ProjectFilesystem rootFilesystem,
      Cell rootCell,
      ObjectMapper objectMapper,
      ParserTargetNodeFactory parserTargetNodeFactory,
      Function<? super TargetNode<?>, ? extends Map<String, Object>> nodeToRawNode) {
    this.rootFilesystem = rootFilesystem;
    this.rootCell = rootCell;
    this.objectMapper = objectMapper;
    this.parserTargetNodeFactory = parserTargetNodeFactory;
    this.nodeToRawNode = nodeToRawNode;
  }

  public BuildJobStateTargetGraph dump(
      Collection<TargetNode<?>> targetNodes) throws InterruptedException {
    BuildJobStateTargetGraph result = new BuildJobStateTargetGraph();
    BiMap<Integer, ProjectFilesystem> filesystemIndex = HashBiMap.create();

    for (TargetNode<?> targetNode : targetNodes) {
      Map<String, Object> rawTargetNode = nodeToRawNode.apply(targetNode);
      ProjectFilesystem projectFilesystem =
          targetNode.getRuleFactoryParams().getProjectFilesystem();

      BuildJobStateTargetNode remoteNode = new BuildJobStateTargetNode();
      remoteNode.setFileSystemRootIndex(getIndex(projectFilesystem, filesystemIndex));
      remoteNode.setBuildTarget(encodeBuildTarget(targetNode.getBuildTarget()));
      try {
        remoteNode.setRawNode(objectMapper.writeValueAsString(rawTargetNode));
      } catch (JsonProcessingException e) {
        throw new RuntimeException(e);
      }
      result.addToNodes(remoteNode);
    }

    result.setFileSystemRoots(
        Maps.transformValues(
            filesystemIndex,
            new Function<ProjectFilesystem, String>() {
              @Override
              public String apply(ProjectFilesystem input) {
                return input.getRootPath().toString();
              }
            }
        ));

    return result;
  }

  private static <T> int getIndex(T value, BiMap<Integer, T> index) {
    Integer i = index.inverse().get(value);
    if (i == null) {
      i = index.size();
      index.put(i, value);
    }
    return i;
  }

  public static BuildJobStateBuildTarget encodeBuildTarget(BuildTarget buildTarget) {
    BuildJobStateBuildTarget remoteTarget = new BuildJobStateBuildTarget();
    remoteTarget.setShortName(buildTarget.getShortName());
    remoteTarget.setBaseName(buildTarget.getBaseName());
    if (buildTarget.getCell().isPresent()) {
      remoteTarget.setCellName(buildTarget.getCell().get());
    }
    remoteTarget.setFlavors(
        FluentIterable.from(buildTarget.getFlavors())
            .transform(Functions.toStringFunction())
            .toSet());
    return remoteTarget;
  }

  public static BuildTarget decodeBuildTarget(BuildJobStateBuildTarget remoteTarget, Cell cell) {

    UnflavoredBuildTarget unflavoredBuildTarget = UnflavoredBuildTarget.builder()
        .setShortName(remoteTarget.getShortName())
        .setBaseName(remoteTarget.getBaseName())
        .setCellPath(cell.getRoot())
        .setCell(Optional.fromNullable(remoteTarget.getCellName()))
        .build();

    ImmutableSet<Flavor> flavors = FluentIterable.from(remoteTarget.flavors)
        .transform(Flavor.TO_FLAVOR)
        .toSet();

    return BuildTarget.builder()
        .setUnflavoredBuildTarget(unflavoredBuildTarget)
        .setFlavors(flavors)
        .build();
  }

  public TargetGraph createTargetGraph(BuildJobStateTargetGraph remoteTargetGraph)
      throws IOException, InterruptedException {
    ImmutableMap.Builder<Integer, Cell> cellBuilder = ImmutableMap.builder();
    // TODO(marcinkosiba): Sort out the story around Cells.
    for (Map.Entry<Integer, String> remoteFileSystemRoot :
        remoteTargetGraph.getFileSystemRoots().entrySet()) {
      final Path remoteFilesystemRoot = Files.createTempDirectory(
          rootFilesystem.resolve(rootFilesystem.getBuckPaths().getBuckOut()),
          "remote_");
      ProjectFilesystem projectFilesystem = new ProjectFilesystem(remoteFilesystemRoot);
      Cell cell = rootCell.createCellForDistributedBuild(
          remoteFilesystemRoot,
          ImmutableMap.of(remoteFilesystemRoot, rootCell.getBuckConfig()),
          ImmutableMap.of(remoteFilesystemRoot, projectFilesystem));
      cellBuilder.put(remoteFileSystemRoot.getKey(), cell);
    }
    ImmutableMap<Integer, Cell> cells = cellBuilder.build();

    ImmutableMap.Builder<BuildTarget, TargetNode<?>> targetNodeIndexBuilder =
        ImmutableMap.builder();

    for (BuildJobStateTargetNode remoteNode : remoteTargetGraph.getNodes()) {
      Cell cell = Preconditions.checkNotNull(cells.get(remoteNode.getFileSystemRootIndex()));
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
          rawNode);
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

    return new TargetGraph(mutableTargetGraph, targetNodeIndex);
  }
}
