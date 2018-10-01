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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphAndBuildTargets;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.nio.file.Path;
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

  TargetNode<?> getTargetNode(
      Cell cell, boolean enableProfiling, ListeningExecutorService executor, BuildTarget target)
      throws BuildFileParseException;

  ImmutableList<TargetNode<?>> getAllTargetNodes(
      PerBuildState perBuildState, Cell cell, Path buildFile) throws BuildFileParseException;

  TargetNode<?> getTargetNode(PerBuildState perBuildState, BuildTarget target)
      throws BuildFileParseException;

  ListenableFuture<TargetNode<?>> getTargetNodeJob(PerBuildState perBuildState, BuildTarget target)
      throws BuildTargetException;

  @Nullable
  SortedMap<String, Object> getTargetNodeRawAttributes(
      PerBuildState state, Cell cell, TargetNode<?> targetNode) throws BuildFileParseException;

  /**
   * @deprecated Prefer {@link #getTargetNodeRawAttributes(PerBuildState, Cell, TargetNode)} and
   *     reusing a PerBuildState instance, especially when calling in a loop.
   */
  @Nullable
  @Deprecated
  SortedMap<String, Object> getTargetNodeRawAttributes(
      Cell cell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      TargetNode<?> targetNode)
      throws BuildFileParseException;

  TargetGraph buildTargetGraph(
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<BuildTarget> toExplore)
      throws IOException, InterruptedException, BuildFileParseException;

  /**
   * @param targetNodeSpecs the specs representing the build targets to generate a target graph for.
   * @return the target graph containing the build targets and their related targets.
   */
  TargetGraphAndBuildTargets buildTargetGraphForTargetNodeSpecs(
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs)
      throws BuildFileParseException, IOException, InterruptedException;

  /**
   * @param targetNodeSpecs the specs representing the build targets to generate a target graph for.
   * @return the target graph containing the build targets and their related targets.
   */
  TargetGraphAndBuildTargets buildTargetGraphForTargetNodeSpecs(
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, IOException, InterruptedException;

  ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> specs,
      SpeculativeParsing speculativeParsing,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws BuildFileParseException, InterruptedException, IOException;
}
