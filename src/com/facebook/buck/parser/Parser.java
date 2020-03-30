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
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphCreationResult;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.util.Optional;
import java.util.SortedMap;
import javax.annotation.Nullable;

/**
 * High-level build file parsing machinery. Primarily responsible for producing a {@link
 * TargetGraph} based on a set of targets. Caches build rules to minimise the number of calls to
 * build file interpreter and processes filesystem events to invalidate the cache as files change.
 */
public interface Parser {

  DaemonicParserState getPermState();

  PerBuildStateFactory getPerBuildStateFactory();

  TargetNode<?> getTargetNodeAssertCompatible(
      ParsingContext parsingContext, BuildTarget target, DependencyStack dependencyStack)
      throws BuildFileParseException;

  ImmutableList<TargetNodeMaybeIncompatible> getAllTargetNodes(
      PerBuildState perBuildState,
      Cell cell,
      AbsPath buildFile,
      Optional<TargetConfiguration> targetConfiguration)
      throws BuildFileParseException;

  ImmutableList<TargetNode<?>> getAllTargetNodesWithTargetCompatibilityFiltering(
      PerBuildState state,
      Cell cell,
      AbsPath buildFile,
      Optional<TargetConfiguration> targetConfiguration)
      throws BuildFileParseException;

  TargetNode<?> getTargetNodeAssertCompatible(
      PerBuildState perBuildState, BuildTarget target, DependencyStack dependencyStack)
      throws BuildFileParseException;

  ListenableFuture<TargetNode<?>> getTargetNodeJobAssertCompatible(
      PerBuildState perBuildState, BuildTarget target, DependencyStack dependencyStack)
      throws BuildTargetException;

  @Nullable
  SortedMap<String, Object> getTargetNodeRawAttributes(
      PerBuildState state, Cell cell, TargetNode<?> targetNode, DependencyStack dependencyStack)
      throws BuildFileParseException;

  ListenableFuture<SortedMap<String, Object>> getTargetNodeRawAttributesJob(
      PerBuildState state, Cell cell, TargetNode<?> targetNode, DependencyStack dependencyStack)
      throws BuildFileParseException;

  /**
   * @deprecated Prefer {@link #getTargetNodeRawAttributes(PerBuildState, Cell, TargetNode,
   *     DependencyStack)} and reusing a PerBuildState instance, especially when calling in a loop.
   */
  @Nullable
  @Deprecated
  SortedMap<String, Object> getTargetNodeRawAttributes(
      ParsingContext parsingContext, TargetNode<?> targetNode, DependencyStack dependencyStack)
      throws BuildFileParseException;

  TargetGraphCreationResult buildTargetGraph(
      ParsingContext parsingContext, ImmutableSet<BuildTarget> toExplore)
      throws IOException, InterruptedException, BuildFileParseException;

  /**
   * @param targetNodeSpecs the specs representing the build targets to generate a target graph for.
   * @param targetConfiguration
   * @return the target graph containing the build targets and their related targets.
   */
  TargetGraphCreationResult buildTargetGraphWithoutTopLevelConfigurationTargets(
      ParsingContext parsingContext,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      Optional<TargetConfiguration> targetConfiguration)
      throws BuildFileParseException, IOException, InterruptedException;

  /**
   * @param targetNodeSpecs the specs representing the build targets to generate a target graph for.
   * @param targetConfiguration
   * @return the target graph containing the build targets and their related targets.
   */
  TargetGraphCreationResult buildTargetGraphWithTopLevelConfigurationTargets(
      ParsingContext parsingContext,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      Optional<TargetConfiguration> targetConfiguration)
      throws BuildFileParseException, IOException, InterruptedException;

  ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      ParsingContext parsingContext,
      Iterable<? extends TargetNodeSpec> specs,
      Optional<TargetConfiguration> targetConfiguration)
      throws BuildFileParseException, InterruptedException;
}
