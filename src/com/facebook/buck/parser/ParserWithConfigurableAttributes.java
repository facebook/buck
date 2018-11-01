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
import com.facebook.buck.core.description.arg.HasTargetCompatibleWith;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.AbstractParserConfig.ApplyDefaultFlavorsMode;
import com.facebook.buck.parser.TargetSpecResolver.TargetNodeFilterForSpecResolver;
import com.facebook.buck.parser.TargetSpecResolver.TargetNodeProviderForSpecResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * An implementation of {@link Parser} that supports attributes with configurable values (i.e.
 * defined using {@code select} keyword).
 *
 * <p>This implementation also supports notion of configuration rules which are used to resolve
 * conditions in {@code select} statements.
 */
class ParserWithConfigurableAttributes extends DefaultParser {

  ParserWithConfigurableAttributes(
      DaemonicParserState daemonicParserState,
      PerBuildStateFactory perBuildStateFactory,
      TargetSpecResolver targetSpecResolver,
      Watchman watchman,
      BuckEventBus eventBus,
      Supplier<ImmutableList<String>> targetPlatforms) {
    super(
        daemonicParserState,
        perBuildStateFactory,
        targetSpecResolver,
        watchman,
        eventBus,
        targetPlatforms);
  }

  @Override
  protected ImmutableSet<BuildTarget> collectBuildTargetsFromTargetNodeSpecs(
      Cell rootCell,
      PerBuildState state,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      boolean excludeUnsupportedTargets,
      boolean excludeConfigurationTargets,
      ApplyDefaultFlavorsMode applyDefaultFlavorsMode)
      throws IOException, InterruptedException {
    PerBuildStateWithConfigurableAttributes stateWithConfigurableAttributes =
        (PerBuildStateWithConfigurableAttributes) state;

    TargetNodeProviderForSpecResolver<TargetNode<?>> targetNodeProvider =
        createTargetNodeProviderForSpecResolver(state);

    TargetNodeFilterForSpecResolver<TargetNode<?>> targetNodeFilter =
        (spec, nodes) -> spec.filter(nodes);

    if (excludeConfigurationTargets) {
      targetNodeFilter =
          new TargetNodeFilterForSpecResolverWithNodeFiltering<>(
              targetNodeFilter, ParserWithConfigurableAttributes::filterOutNonBuildTargets);
    }

    if (excludeUnsupportedTargets) {
      Platform targetPlatform = stateWithConfigurableAttributes.getTargetPlatform().get();
      ConstraintResolver constraintResolver =
          stateWithConfigurableAttributes.getConstraintResolver();
      targetNodeFilter =
          new TargetNodeFilterForSpecResolverWithNodeFiltering<>(
              targetNodeFilter,
              node -> targetNodeMatchesPlatform(constraintResolver, node, targetPlatform));
    }

    return ImmutableSet.copyOf(
        Iterables.concat(
            targetSpecResolver.resolveTargetSpecs(
                eventBus,
                rootCell,
                watchman,
                targetNodeSpecs,
                (buildTarget, targetNode, targetType) ->
                    applyDefaultFlavors(
                        buildTarget, targetNode, targetType, applyDefaultFlavorsMode),
                targetNodeProvider,
                targetNodeFilter)));
  }

  private static boolean filterOutNonBuildTargets(TargetNode<?> node) {
    return node.getRuleType().getKind() == RuleType.Kind.BUILD;
  }

  private boolean targetNodeMatchesPlatform(
      ConstraintResolver constraintResolver, TargetNode<?> targetNode, Platform platform) {
    if (!(targetNode.getConstructorArg() instanceof HasTargetCompatibleWith)) {
      return true;
    }
    HasTargetCompatibleWith argWithTargetCompatible =
        (HasTargetCompatibleWith) targetNode.getConstructorArg();

    return platform.matchesAll(
        argWithTargetCompatible
            .getTargetCompatibleWith()
            .stream()
            .map(constraintResolver::getConstraintValue)
            .collect(Collectors.toList()));
  }
}
