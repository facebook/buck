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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.thrift.BuildJobStateBuildTarget;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetGraph;
import com.facebook.buck.distributed.thrift.BuildJobStateTargetNode;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Saves and loads the {@link TargetNode}s needed for the build. */
public class DistBuildTargetGraphCodec {
  private static final Logger LOG = Logger.get(DistBuildTargetGraphCodec.class);

  private ListeningExecutorService cpuExecutor;
  private final Function<? super TargetNode<?>, ? extends Map<String, Object>> nodeToRawNode;

  public DistBuildTargetGraphCodec(
      ListeningExecutorService cpuExecutor,
      Function<? super TargetNode<?>, ? extends Map<String, Object>> nodeToRawNode) {
    this.cpuExecutor = cpuExecutor;
    this.nodeToRawNode = nodeToRawNode;
  }

  public BuildJobStateTargetGraph dump(
      Collection<TargetNode<?>> targetNodes, DistBuildCellIndexer cellIndexer)
      throws InterruptedException {
    List<ListenableFuture<BuildJobStateTargetNode>> targetNodeFutures = new LinkedList<>();

    for (TargetNode<?> targetNode : targetNodes) {
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
      DistBuildCellIndexer cellIndexer, TargetNode<?> targetNode) {
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
    if (buildTarget.getCell().getLegacyName().isPresent()) {
      remoteTarget.setCellName(buildTarget.getCell().getLegacyName().get());
    }
    remoteTarget.setFlavors(
        buildTarget.getFlavors().stream().map(Object::toString).collect(Collectors.toSet()));
    return remoteTarget;
  }
}
