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
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.HasTargetCompatibleWith;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.core.select.impl.SelectorFactory;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.AbstractParserConfig.ApplyDefaultFlavorsMode;
import com.facebook.buck.parser.TargetSpecResolver.TargetNodeFilterForSpecResolver;
import com.facebook.buck.parser.TargetSpecResolver.TargetNodeProviderForSpecResolver;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.rules.coercer.BuildTargetTypeCoercer;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.JsonTypeConcatenatingCoercerFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

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

  /**
   * This implementation collects raw attributes of a target node and resolves configurable
   * attributes.
   */
  @Override
  @Nullable
  public SortedMap<String, Object> getTargetNodeRawAttributes(
      PerBuildState state, Cell cell, TargetNode<?> targetNode) throws BuildFileParseException {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    Cell owningCell = cell.getCell(buildTarget);
    BuildFileManifest buildFileManifest =
        getTargetNodeRawAttributes(state, owningCell, cell.getAbsolutePathToBuildFile(buildTarget));
    String shortName = buildTarget.getShortName();

    if (!buildFileManifest.getTargets().containsKey(shortName)) {
      return null;
    }

    Map<String, Object> attributes = buildFileManifest.getTargets().get(shortName);

    SortedMap<String, Object> convertedAttributes =
        copyWithResolvingConfigurableAttributes(state, cell, buildTarget, attributes);

    convertedAttributes.put(
        InternalTargetAttributeNames.DIRECT_DEPENDENCIES,
        targetNode
            .getParseDeps()
            .stream()
            .map(Object::toString)
            .collect(ImmutableList.toImmutableList()));
    return convertedAttributes;
  }

  private SortedMap<String, Object> copyWithResolvingConfigurableAttributes(
      PerBuildState state, Cell cell, BuildTarget buildTarget, Map<String, Object> attributes) {
    PerBuildStateWithConfigurableAttributes stateWithConfigurableAttributes =
        (PerBuildStateWithConfigurableAttributes) state;

    SelectableConfigurationContext configurationContext =
        DefaultSelectableConfigurationContext.of(
            cell.getBuckConfig(),
            stateWithConfigurableAttributes.getConstraintResolver(),
            stateWithConfigurableAttributes.getTargetPlatform().get());

    SelectorListFactory selectorListFactory =
        new SelectorListFactory(new SelectorFactory(new BuildTargetTypeCoercer()::coerce));

    SortedMap<String, Object> convertedAttributes = new TreeMap<>();

    for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
      String attributeName = attribute.getKey();
      try {
        convertedAttributes.put(
            attributeName,
            resolveConfigurableAttributes(
                stateWithConfigurableAttributes.getSelectorListResolver(),
                configurationContext,
                cell.getCellPathResolver(),
                cell.getFilesystem(),
                buildTarget,
                selectorListFactory,
                attributeName,
                attribute.getValue()));
      } catch (CoerceFailedException e) {
        throw new HumanReadableException(e, "%s: %s", buildTarget, e.getMessage());
      }
    }

    return convertedAttributes;
  }

  @Nullable
  private Object resolveConfigurableAttributes(
      SelectorListResolver selectorListResolver,
      SelectableConfigurationContext configurationContext,
      CellPathResolver cellPathResolver,
      ProjectFilesystem projectFilesystem,
      BuildTarget buildTarget,
      SelectorListFactory selectorListFactory,
      String attributeName,
      Object jsonObject)
      throws CoerceFailedException {
    if (!(jsonObject instanceof ListWithSelects)) {
      return jsonObject;
    }

    ListWithSelects list = (ListWithSelects) jsonObject;

    SelectorList<Object> selectorList =
        selectorListFactory.create(
            cellPathResolver,
            projectFilesystem,
            buildTarget.getBasePath(),
            list.getElements(),
            JsonTypeConcatenatingCoercerFactory.createForType(list.getType()));

    return selectorListResolver.resolveList(
        configurationContext, buildTarget, attributeName, selectorList);
  }

  @Override
  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      Cell rootCell,
      boolean enableProfiling,
      ListeningExecutorService executor,
      Iterable<? extends TargetNodeSpec> specs,
      SpeculativeParsing speculativeParsing,
      ParserConfig.ApplyDefaultFlavorsMode applyDefaultFlavorsMode,
      boolean excludeUnsupportedTargets)
      throws BuildFileParseException, InterruptedException, IOException {

    try (PerBuildStateWithConfigurableAttributes state =
        (PerBuildStateWithConfigurableAttributes)
            perBuildStateFactory.create(
                permState,
                executor,
                rootCell,
                targetPlatforms.get(),
                enableProfiling,
                speculativeParsing)) {
      TargetNodeFilterForSpecResolver<TargetNode<?>> targetNodeFilter =
          (spec, nodes) -> spec.filter(nodes);
      if (excludeUnsupportedTargets) {
        Platform targetPlatform = state.getTargetPlatform().get();
        ConstraintResolver constraintResolver = state.getConstraintResolver();
        targetNodeFilter =
            new TargetNodeFilterForSpecResolverWithNodeFiltering<>(
                targetNodeFilter,
                node -> targetNodeMatchesPlatform(constraintResolver, node, targetPlatform));
      }

      TargetNodeProviderForSpecResolver<TargetNode<?>> targetNodeProvider =
          createTargetNodeProviderForSpecResolver(state);
      return targetSpecResolver.resolveTargetSpecs(
          eventBus,
          rootCell,
          watchman,
          specs,
          (buildTarget, targetNode, targetType) ->
              applyDefaultFlavors(buildTarget, targetNode, targetType, applyDefaultFlavorsMode),
          targetNodeProvider,
          targetNodeFilter);
    }
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
