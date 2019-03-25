/*
 * Copyright 2015-present Facebook, Inc.
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
import com.facebook.buck.core.description.attr.ImplicitFlavorsInferringDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.HasDefaultFlavors;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.parser.TargetSpecResolver.TargetNodeProviderForSpecResolver;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.exceptions.BuildTargetException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Evaluates build files using one of the supported interpreters and provides information about
 * build targets defined in them.
 *
 * <p>Computed targets are cached but are automatically invalidated if Watchman reports any
 * filesystem changes that may affect computed results.
 */
// TODO: remove after migration to configurable attributes
class DefaultParser extends AbstractParser {

  private static final Logger LOG = Logger.get(Parser.class);

  protected final TargetSpecResolver targetSpecResolver;

  DefaultParser(
      DaemonicParserState daemonicParserState,
      PerBuildStateFactory perBuildStateFactory,
      TargetSpecResolver targetSpecResolver,
      BuckEventBus eventBus,
      Supplier<ImmutableList<String>> targetPlatforms) {
    super(daemonicParserState, perBuildStateFactory, eventBus, targetPlatforms);
    this.targetSpecResolver = targetSpecResolver;
  }

  @Override
  protected ImmutableSet<BuildTarget> collectBuildTargetsFromTargetNodeSpecs(
      ParsingContext parsingContext,
      PerBuildState state,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      TargetConfiguration targetConfiguration,
      boolean excludeConfigurationTargets)
      throws InterruptedException {
    TargetNodeProviderForSpecResolver<TargetNode<?>> targetNodeProvider =
        createTargetNodeProviderForSpecResolver(state);

    return ImmutableSet.copyOf(
        Iterables.concat(
            targetSpecResolver.resolveTargetSpecs(
                parsingContext.getCell(),
                targetNodeSpecs,
                // This parser doesn't support configured targets, explicitly erase information
                // about target configuration
                EmptyTargetConfiguration.INSTANCE,
                (buildTarget, targetNode, targetType) ->
                    applyDefaultFlavors(
                        buildTarget,
                        targetNode,
                        targetType,
                        parsingContext.getApplyDefaultFlavorsMode()),
                targetNodeProvider,
                (spec, nodes) -> spec.filter(nodes))));
  }

  static TargetNodeProviderForSpecResolver<TargetNode<?>> createTargetNodeProviderForSpecResolver(
      PerBuildState state) {
    return new TargetNodeProviderForSpecResolver<TargetNode<?>>() {
      @Override
      public ListenableFuture<TargetNode<?>> getTargetNodeJob(BuildTarget target)
          throws BuildTargetException {
        return state.getTargetNodeJob(target);
      }

      @Override
      public ListenableFuture<ImmutableList<TargetNode<?>>> getAllTargetNodesJob(
          Cell cell, Path buildFile, TargetConfiguration targetConfiguration)
          throws BuildTargetException {
        return state.getAllTargetNodesJob(cell, buildFile, targetConfiguration);
      }
    };
  }

  @Override
  public ImmutableList<TargetNode<?>> getAllTargetNodesWithTargetCompatibilityFiltering(
      PerBuildState state, Cell cell, Path buildFile, TargetConfiguration targetConfiguration)
      throws BuildFileParseException {
    return getAllTargetNodes(state, cell, buildFile, targetConfiguration);
  }

  @Override
  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      ParsingContext parsingContext,
      Iterable<? extends TargetNodeSpec> specs,
      TargetConfiguration targetConfiguration)
      throws BuildFileParseException, InterruptedException {

    try (PerBuildState state =
        perBuildStateFactory.create(parsingContext, permState, targetPlatforms.get())) {
      TargetNodeProviderForSpecResolver<TargetNode<?>> targetNodeProvider =
          createTargetNodeProviderForSpecResolver(state);
      return targetSpecResolver.resolveTargetSpecs(
          parsingContext.getCell(),
          specs,
          // This parser doesn't support configured targets, explicitly erase information
          // about target configuration
          EmptyTargetConfiguration.INSTANCE,
          (buildTarget, targetNode, targetType) ->
              applyDefaultFlavors(
                  buildTarget, targetNode, targetType, parsingContext.getApplyDefaultFlavorsMode()),
          targetNodeProvider,
          (spec, nodes) -> spec.filter(nodes));
    }
  }

  @VisibleForTesting
  static BuildTarget applyDefaultFlavors(
      BuildTarget target,
      TargetNode<?> targetNode,
      TargetNodeSpec.TargetType targetType,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode) {
    if (target.isFlavored()
        || (targetType == TargetNodeSpec.TargetType.MULTIPLE_TARGETS
            && applyDefaultFlavorsMode == ParserConfig.ApplyDefaultFlavorsMode.SINGLE)
        || applyDefaultFlavorsMode == ParserConfig.ApplyDefaultFlavorsMode.DISABLED) {
      return target;
    }

    ImmutableSortedSet<Flavor> defaultFlavors = ImmutableSortedSet.of();
    if (targetNode.getConstructorArg() instanceof HasDefaultFlavors) {
      defaultFlavors = ((HasDefaultFlavors) targetNode.getConstructorArg()).getDefaultFlavors();
      LOG.debug("Got default flavors %s from args of %s", defaultFlavors, target);
    }

    if (targetNode.getDescription() instanceof ImplicitFlavorsInferringDescription) {
      defaultFlavors =
          ((ImplicitFlavorsInferringDescription) targetNode.getDescription())
              .addImplicitFlavors(defaultFlavors);
      LOG.debug("Got default flavors %s from description of %s", defaultFlavors, target);
    }

    return target.withFlavors(defaultFlavors);
  }
}
