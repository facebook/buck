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

package com.facebook.buck.cli;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.graph.DirectedAcyclicGraph;
import com.facebook.buck.parser.ParsingContext;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.query.QueryException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import java.util.Set;

/** Interface which describes the universe of targets accessible to queries */
public interface TargetUniverse {

  /**
   * The target graph representing the contents of the universe. This state may be mutated during
   * the execution of the query based on calls to {@link #buildTransitiveClosure(Set)}.
   */
  DirectedAcyclicGraph<TargetNode<?>> getTargetGraph();

  /**
   * Returns the {@code buildTarget}s in the universe that are referenced by {@code specs}. These
   * targets will be configured for {@code targetConfiguration}, if given. Some implementations may
   * need to delegate to the parser, in which case they can use the given {@code ParsingContext}.
   */
  ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      Iterable<? extends TargetNodeSpec> specs,
      Optional<TargetConfiguration> targetConfiguration,
      ParsingContext parsingContext)
      throws BuildFileParseException, InterruptedException;

  /**
   * Returns the {@code TargetNode} in the universe for {@code buildTarget}. Throws a {@code
   * QueryException} if no node exists for the target.
   */
  TargetNode<?> getNode(BuildTarget buildTarget) throws QueryException;

  ImmutableList<TargetNode<?>> getAllTargetNodesWithTargetCompatibilityFiltering(
      Cell cell, AbsPath buildFile, Optional<TargetConfiguration> targetConfiguration);

  /**
   * Returns the forward transitive closure of all of the targets in "targets". Callers must ensure
   * that {@link #buildTransitiveClosure} has been called for the relevant subgraph.
   */
  ImmutableSet<BuildTarget> getTransitiveClosure(Set<BuildTarget> targets) throws QueryException;

  /**
   * Construct the dependency graph for a depth-bounded forward transitive closure of all nodes in
   * "targetNodes". Callers are in charge of invoking this method before using {@link
   * #getTransitiveClosure} on those targets.
   */
  void buildTransitiveClosure(Set<BuildTarget> targets) throws QueryException;

  /**
   * Returns the set of targets referenced via outgoing edges from `target`. Put another way,
   * returns the dependencies of `target`.
   */
  ImmutableSet<BuildTarget> getAllTargetsFromOutgoingEdgesOf(BuildTarget target);

  /**
   * Returns the set of targets referenced via incoming edges from `target`. Put another way,
   * returns the reverse dependencies of `target`.
   */
  ImmutableSet<BuildTarget> getAllTargetsFromIncomingEdgesOf(BuildTarget target);
}