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
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.facebook.buck.parser.exceptions.MissingBuildFileException;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.MoreThrowables;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/** Responsible for discovering all the build targets that match a set of {@link TargetNodeSpec}. */
public class TargetSpecResolver {

  /**
   * @return a list of sets of build targets where each set contains all build targets that match a
   *     corresponding {@link TargetNodeSpec}.
   */
  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      PerBuildState state,
      BuckEventBus eventBus,
      Cell rootCell,
      Iterable<? extends TargetNodeSpec> specs,
      FlavorEnhancer flavorEnhancer)
      throws BuildFileParseException, InterruptedException, IOException {

    ParserConfig parserConfig = rootCell.getBuckConfig().getView(ParserConfig.class);
    ParserConfig.BuildFileSearchMethod buildFileSearchMethod =
        parserConfig.getBuildFileSearchMethod();

    // Convert the input spec iterable into a list so we have a fixed ordering, which we'll rely on
    // when returning results.
    ImmutableList<TargetNodeSpec> orderedSpecs = ImmutableList.copyOf(specs);

    // Resolve all the build files from all the target specs.  We store these into a multi-map which
    // maps the path to the build file to the index of it's spec file in the ordered spec list.
    Multimap<Path, Integer> perBuildFileSpecs = LinkedHashMultimap.create();
    for (int index = 0; index < orderedSpecs.size(); index++) {
      TargetNodeSpec spec = orderedSpecs.get(index);
      Cell cell = rootCell.getCell(spec.getBuildFileSpec().getCellPath());
      ImmutableSet<Path> buildFiles;
      try (SimplePerfEvent.Scope perfEventScope =
          SimplePerfEvent.scope(
              eventBus, PerfEventId.of("FindBuildFiles"), "targetNodeSpec", spec)) {
        // Iterate over the build files the given target node spec returns.
        buildFiles = spec.getBuildFileSpec().findBuildFiles(cell, buildFileSearchMethod);
      }
      for (Path buildFile : buildFiles) {
        perBuildFileSpecs.put(buildFile, index);
      }
    }

    // Kick off parse futures for each build file.
    ArrayList<ListenableFuture<Map.Entry<Integer, ImmutableSet<BuildTarget>>>> targetFutures =
        new ArrayList<>();
    for (Path buildFile : perBuildFileSpecs.keySet()) {
      Collection<Integer> buildFileSpecs = perBuildFileSpecs.get(buildFile);
      TargetNodeSpec firstSpec = orderedSpecs.get(Iterables.get(buildFileSpecs, 0));
      Cell cell = rootCell.getCell(firstSpec.getBuildFileSpec().getCellPath());

      // Format a proper error message for non-existent build files.
      if (!cell.getFilesystem().isFile(buildFile)) {
        throw new MissingBuildFileException(
            firstSpec.toString(), cell.getFilesystem().getRootPath().relativize(buildFile));
      }

      for (int index : buildFileSpecs) {
        TargetNodeSpec spec = orderedSpecs.get(index);
        if (spec instanceof BuildTargetSpec) {
          BuildTargetSpec buildTargetSpec = (BuildTargetSpec) spec;
          targetFutures.add(
              Futures.transform(
                  state.getTargetNodeJob(buildTargetSpec.getBuildTarget()),
                  node -> {
                    ImmutableSet<BuildTarget> buildTargets =
                        applySpecFilter(spec, ImmutableSet.of(node), flavorEnhancer);
                    Preconditions.checkState(
                        buildTargets.size() == 1,
                        "BuildTargetSpec %s filter discarded target %s, but was not supposed to.",
                        spec,
                        node.getBuildTarget());
                    return new AbstractMap.SimpleEntry<>(index, buildTargets);
                  },
                  MoreExecutors.directExecutor()));
        } else {
          // Build up a list of all target nodes from the build file.
          targetFutures.add(
              Futures.transform(
                  state.getAllTargetNodesJob(cell, buildFile),
                  nodes ->
                      new AbstractMap.SimpleEntry<>(
                          index, applySpecFilter(spec, nodes, flavorEnhancer)),
                  MoreExecutors.directExecutor()));
        }
      }
    }

    // Now walk through and resolve all the futures, and place their results in a multimap that
    // is indexed by the integer representing the input target spec order.
    LinkedHashMultimap<Integer, BuildTarget> targetsMap = LinkedHashMultimap.create();
    try {
      for (ListenableFuture<Map.Entry<Integer, ImmutableSet<BuildTarget>>> targetFuture :
          targetFutures) {
        Map.Entry<Integer, ImmutableSet<BuildTarget>> result = targetFuture.get();
        targetsMap.putAll(result.getKey(), result.getValue());
      }
    } catch (ExecutionException e) {
      MoreThrowables.throwIfAnyCauseInstanceOf(e, BuildFileParseException.class);
      MoreThrowables.throwIfAnyCauseInstanceOf(e, BuildTargetException.class);
      MoreThrowables.throwIfAnyCauseInstanceOf(e, HumanReadableException.class);
      MoreThrowables.throwIfAnyCauseInstanceOf(e, InterruptedException.class);
      Throwables.throwIfUnchecked(e.getCause());
      throw new RuntimeException(e);
    }

    // Finally, pull out the final build target results in input target spec order, and place them
    // into a list of sets that exactly matches the ihput order.
    ImmutableList.Builder<ImmutableSet<BuildTarget>> targets = ImmutableList.builder();
    for (int index = 0; index < orderedSpecs.size(); index++) {
      targets.add(ImmutableSet.copyOf(targetsMap.get(index)));
    }
    return targets.build();
  }

  private ImmutableSet<BuildTarget> applySpecFilter(
      TargetNodeSpec spec,
      ImmutableSet<TargetNode<?, ?>> targetNodes,
      FlavorEnhancer flavorEnhancer) {
    ImmutableSet.Builder<BuildTarget> targets = ImmutableSet.builder();
    ImmutableMap<BuildTarget, Optional<TargetNode<?, ?>>> partialTargets = spec.filter(targetNodes);
    for (Map.Entry<BuildTarget, Optional<TargetNode<?, ?>>> partialTarget :
        partialTargets.entrySet()) {
      BuildTarget target =
          flavorEnhancer.enhanceFlavors(
              partialTarget.getKey(), partialTarget.getValue(), spec.getTargetType());
      targets.add(target);
    }
    return targets.build();
  }

  /** Allows to change flavors of some targets while performing the resolution. */
  public interface FlavorEnhancer {
    BuildTarget enhanceFlavors(
        BuildTarget target,
        Optional<TargetNode<?, ?>> targetNode,
        TargetNodeSpec.TargetType targetType);
  }
}
