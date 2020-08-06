/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Optional;

public class PerBuildState implements AutoCloseable {

  private final CellManager cellManager;
  private final BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline;
  private final UnconfiguredTargetNodeToTargetNodeParsePipeline targetNodeParsePipeline;
  private final ParsingContext parsingContext;
  private final SelectorListResolver selectorListResolver;
  private final SelectorListFactory selectorListFactory;
  private final ConfigurationRuleRegistry configurationRuleRegistry;

  PerBuildState(
      CellManager cellManager,
      BuildFileRawNodeParsePipeline buildFileRawNodeParsePipeline,
      UnconfiguredTargetNodeToTargetNodeParsePipeline targetNodeParsePipeline,
      ParsingContext parsingContext,
      SelectorListResolver selectorListResolver,
      SelectorListFactory selectorListFactory,
      ConfigurationRuleRegistry configurationRuleRegistry) {
    this.cellManager = cellManager;
    this.buildFileRawNodeParsePipeline = buildFileRawNodeParsePipeline;
    this.targetNodeParsePipeline = targetNodeParsePipeline;
    this.parsingContext = parsingContext;
    this.selectorListResolver = selectorListResolver;
    this.selectorListFactory = selectorListFactory;
    this.configurationRuleRegistry = configurationRuleRegistry;
  }

  TargetNodeMaybeIncompatible getTargetNode(BuildTarget target, DependencyStack dependencyStack)
      throws BuildFileParseException {
    Cell owningCell = cellManager.getCell(target.getCell());

    return targetNodeParsePipeline.getNode(owningCell, target, dependencyStack);
  }

  TargetNode<?> getTargetNodeAssertCompatible(BuildTarget target, DependencyStack dependencyStack) {
    Cell owningCell = cellManager.getCell(target.getCell());

    return targetNodeParsePipeline
        .getNode(owningCell, target, dependencyStack)
        .assertGetTargetNode(dependencyStack);
  }

  ListenableFuture<TargetNode<?>> getTargetNodeJobAssertCompatible(
      BuildTarget target, DependencyStack dependencyStack) throws BuildTargetException {
    Cell owningCell = cellManager.getCell(target.getCell());

    return Futures.transform(
        targetNodeParsePipeline.getNodeJob(owningCell, target, dependencyStack),
        targetNodeMaybeIncompatible ->
            targetNodeMaybeIncompatible.assertGetTargetNode(dependencyStack));
  }

  ImmutableList<TargetNodeMaybeIncompatible> getAllTargetNodes(
      Cell cell, AbsPath buildFile, Optional<TargetConfiguration> targetConfiguration)
      throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    return targetNodeParsePipeline.getAllRequestedTargetNodes(cell, buildFile, targetConfiguration);
  }

  ListenableFuture<TargetNodeMaybeIncompatible> getRequestedTargetNodeJob(
      UnconfiguredBuildTarget target, Optional<TargetConfiguration> targetConfiguration) {
    Cell owningCell = cellManager.getCell(target.getCell());

    return targetNodeParsePipeline.getRequestedTargetNodeJob(
        owningCell, target, targetConfiguration);
  }

  ListenableFuture<ImmutableList<TargetNodeMaybeIncompatible>> getRequestedTargetNodesJob(
      Cell cell, AbsPath buildFile, Optional<TargetConfiguration> targetConfiguration)
      throws BuildTargetException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));

    return targetNodeParsePipeline.getAllRequestedTargetNodesJob(
        cell, buildFile, targetConfiguration);
  }

  public BuildFileManifest getBuildFileManifest(Cell cell, AbsPath buildFile)
      throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));
    return buildFileRawNodeParsePipeline.getFile(cell, buildFile);
  }

  ListenableFuture<BuildFileManifest> getBuildFileManifestJob(Cell cell, AbsPath buildFile)
      throws BuildFileParseException {
    Preconditions.checkState(buildFile.startsWith(cell.getRoot()));
    return buildFileRawNodeParsePipeline.getFileJob(cell, buildFile);
  }

  ParsingContext getParsingContext() {
    return parsingContext;
  }

  SelectorListResolver getSelectorListResolver() {
    return selectorListResolver;
  }

  SelectorListFactory getSelectorListFactory() {
    return selectorListFactory;
  }

  ConfigurationRuleRegistry getConfigurationRuleRegistry() {
    return configurationRuleRegistry;
  }

  @Override
  public void close() {
    targetNodeParsePipeline.close();
    buildFileRawNodeParsePipeline.close();
    cellManager.close();
  }
}
