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
package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Map;

/** A pipeline that provides access to a raw node by its {@link BuildTarget}. */
public class BuildTargetRawNodeParsePipeline
    implements BuildTargetParsePipeline<Map<String, Object>> {

  private final ListeningExecutorService executorService;
  private final BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline;

  public BuildTargetRawNodeParsePipeline(
      ListeningExecutorService executorService,
      BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline) {
    this.executorService = executorService;
    this.buildFileRawNodeParsePipeline = buildFileRawNodeParsePipeline;
  }

  @Override
  public ListenableFuture<Map<String, Object>> getNodeJob(
      Cell cell, UnconfiguredBuildTargetView buildTarget) throws BuildTargetException {
    return Futures.transformAsync(
        buildFileRawNodeParsePipeline.getAllNodesJob(
            cell,
            cell.getBuckConfigView(ParserConfig.class)
                .getAbsolutePathToBuildFile(cell, buildTarget)),
        input -> {
          if (!input.getTargets().containsKey(buildTarget.getShortName())) {
            throw NoSuchBuildTargetException.createForMissingBuildRule(
                buildTarget,
                cell.getBuckConfigView(ParserConfig.class)
                    .getAbsolutePathToBuildFile(cell, buildTarget));
          }
          return Futures.immediateFuture(input.getTargets().get(buildTarget.getShortName()));
        },
        executorService);
  }

  @Override
  public void close() {
    buildFileRawNodeParsePipeline.close();
  }
}
