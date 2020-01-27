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
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.attr.ImplicitFlavorsInferringDescription;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.HasDefaultFlavors;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.parser.TargetSpecResolver.TargetNodeFilterForSpecResolver;
import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.parser.config.ParserConfig;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.spec.TargetNodeSpec;
import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * An implementation of {@link Parser} that supports attributes with configurable values (i.e.
 * defined using {@code select} keyword).
 *
 * <p>This implementation also supports notion of configuration rules which are used to resolve
 * conditions in {@code select} statements.
 */
class ParserWithConfigurableAttributes extends AbstractParser {

  private static final Logger LOG = Logger.get(ParserWithConfigurableAttributes.class);

  private final TargetSpecResolver targetSpecResolver;

  ParserWithConfigurableAttributes(
      DaemonicParserState daemonicParserState,
      PerBuildStateFactory perBuildStateFactory,
      TargetSpecResolver targetSpecResolver,
      BuckEventBus eventBus) {
    super(daemonicParserState, perBuildStateFactory, eventBus);
    this.targetSpecResolver = targetSpecResolver;
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
              .addImplicitFlavors(defaultFlavors, target.getTargetConfiguration());
      LOG.debug("Got default flavors %s from description of %s", defaultFlavors, target);
    }

    return target.withFlavors(defaultFlavors);
  }

  /**
   * This implementation collects raw attributes of a target node and resolves configurable
   * attributes.
   */
  @Override
  @Nullable
  public SortedMap<String, Object> getTargetNodeRawAttributes(
      PerBuildState state, Cell cell, TargetNode<?> targetNode, DependencyStack dependencyStack)
      throws BuildFileParseException {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    Cell owningCell = cell.getCell(buildTarget.getCell());
    BuildFileManifest buildFileManifest =
        getTargetNodeRawAttributes(
            state,
            owningCell,
            cell.getBuckConfigView(ParserConfig.class)
                .getAbsolutePathToBuildFile(cell, buildTarget.getUnconfiguredBuildTargetView()));
    return getTargetFromManifest(state, cell, targetNode, dependencyStack, buildFileManifest);
  }

  @Override
  public ListenableFuture<SortedMap<String, Object>> getTargetNodeRawAttributesJob(
      PerBuildState state, Cell cell, TargetNode<?> targetNode, DependencyStack dependencyStack)
      throws BuildFileParseException {
    Cell owningCell = cell.getCell(targetNode.getBuildTarget().getCell());
    ListenableFuture<BuildFileManifest> buildFileManifestFuture =
        state.getBuildFileManifestJob(
            owningCell,
            cell.getBuckConfigView(ParserConfig.class)
                .getAbsolutePathToBuildFile(
                    cell, targetNode.getBuildTarget().getUnconfiguredBuildTargetView()));
    return Futures.transform(
        buildFileManifestFuture,
        buildFileManifest ->
            getTargetFromManifest(state, cell, targetNode, dependencyStack, buildFileManifest),
        MoreExecutors.directExecutor());
  }

  @Nullable
  private SortedMap<String, Object> getTargetFromManifest(
      PerBuildState state,
      Cell cell,
      TargetNode<?> targetNode,
      DependencyStack dependencyStack,
      BuildFileManifest buildFileManifest) {
    BuildTarget buildTarget = targetNode.getBuildTarget();
    String shortName = buildTarget.getShortName();

    if (!buildFileManifest.getTargets().containsKey(shortName)) {
      return null;
    }

    Map<String, Object> attributes = buildFileManifest.getTargets().get(shortName);

    SortedMap<String, Object> convertedAttributes =
        copyWithResolvingConfigurableAttributes(
            state, cell, buildTarget, attributes, dependencyStack);

    convertedAttributes.put(
        InternalTargetAttributeNames.DIRECT_DEPENDENCIES,
        targetNode.getParseDeps().stream()
            .map(Object::toString)
            .collect(ImmutableList.toImmutableList()));
    return convertedAttributes;
  }

  private SortedMap<String, Object> copyWithResolvingConfigurableAttributes(
      PerBuildState state,
      Cell cell,
      BuildTarget buildTarget,
      Map<String, Object> attributes,
      DependencyStack dependencyStack) {
    SelectableConfigurationContext configurationContext =
        ImmutableDefaultSelectableConfigurationContext.of(
            cell.getBuckConfig(),
            buildTarget.getTargetConfiguration(),
            state.getConfigurationRuleRegistry().getTargetPlatformResolver());

    SortedMap<String, Object> convertedAttributes = new TreeMap<>();

    for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
      String attributeName = attribute.getKey();
      try {
        convertedAttributes.put(
            attributeName,
            resolveConfigurableAttributes(
                state.getSelectorListResolver(),
                configurationContext,
                cell.getCellPathResolver(),
                buildTarget,
                state.getSelectorListFactory(),
                attributeName,
                attribute.getValue(),
                dependencyStack));
      } catch (CoerceFailedException e) {
        throw new HumanReadableException(e, dependencyStack, e.getMessage());
      }
    }

    return convertedAttributes;
  }

  @Nullable
  private Object resolveConfigurableAttributes(
      SelectorListResolver selectorListResolver,
      SelectableConfigurationContext configurationContext,
      CellPathResolver cellPathResolver,
      BuildTarget buildTarget,
      SelectorListFactory selectorListFactory,
      String attributeName,
      Object jsonObject,
      DependencyStack dependencyStack)
      throws CoerceFailedException {
    if (!(jsonObject instanceof ListWithSelects)) {
      return jsonObject;
    }

    SelectorList<Object> selectorList =
        selectorListFactory.create(
            cellPathResolver,
            buildTarget.getCellRelativeBasePath().getPath(),
            (ListWithSelects) jsonObject);

    return selectorListResolver.resolveList(
        configurationContext, buildTarget, attributeName, selectorList, dependencyStack);
  }

  @Override
  public ImmutableList<TargetNode<?>> getAllTargetNodesWithTargetCompatibilityFiltering(
      PerBuildState state,
      Cell cell,
      Path buildFile,
      Optional<TargetConfiguration> targetConfiguration)
      throws BuildFileParseException {
    ImmutableList<TargetNode<?>> allTargetNodes =
        getAllTargetNodes(state, cell, buildFile, targetConfiguration);

    if (!state.getParsingContext().excludeUnsupportedTargets()) {
      return allTargetNodes;
    }

    return filterIncompatibleTargetNodes(
            state, getAllTargetNodes(state, cell, buildFile, targetConfiguration).stream())
        .collect(ImmutableList.toImmutableList());
  }

  private Stream<TargetNode<?>> filterIncompatibleTargetNodes(
      PerBuildState state, Stream<TargetNode<?>> targetNodes) {
    return targetNodes.filter(
        targetNode ->
            TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
                state.getConfigurationRuleRegistry(),
                targetNode.getConstructorArg(),
                state
                    .getConfigurationRuleRegistry()
                    .getTargetPlatformResolver()
                    .getTargetPlatform(
                        targetNode.getBuildTarget().getTargetConfiguration(),
                        DependencyStack.top(targetNode.getBuildTarget())),
                DependencyStack.top(targetNode.getBuildTarget()),
                state.getParsingContext().getCell().getBuckConfig()));
  }

  @Override
  public ImmutableList<ImmutableSet<BuildTarget>> resolveTargetSpecs(
      ParsingContext parsingContext,
      Iterable<? extends TargetNodeSpec> specs,
      Optional<TargetConfiguration> targetConfiguration)
      throws BuildFileParseException, InterruptedException {

    try (PerBuildState state = perBuildStateFactory.create(parsingContext, permState)) {
      TargetNodeFilterForSpecResolver targetNodeFilter = TargetNodeSpec::filter;

      ImmutableList<ImmutableSet<BuildTarget>> buildTargets =
          targetSpecResolver.resolveTargetSpecs(
              parsingContext.getCell(),
              specs,
              targetConfiguration,
              (buildTarget, targetNode, targetType) ->
                  applyDefaultFlavors(
                      buildTarget,
                      targetNode,
                      targetType,
                      parsingContext.getApplyDefaultFlavorsMode()),
              state,
              targetNodeFilter);

      if (!state.getParsingContext().excludeUnsupportedTargets()) {
        return buildTargets;
      }
      return buildTargets.stream()
          .map(
              targets ->
                  filterIncompatibleTargetNodes(
                          state,
                          targets.stream()
                              .map(
                                  (BuildTarget target) ->
                                      state.getTargetNode(target, DependencyStack.top(target))))
                      .map(TargetNode::getBuildTarget)
                      .collect(ImmutableSet.toImmutableSet()))
          .collect(ImmutableList.toImmutableList());
    }
  }

  @Override
  protected ImmutableSet<BuildTarget> collectBuildTargetsFromTargetNodeSpecs(
      ParsingContext parsingContext,
      PerBuildState state,
      Iterable<? extends TargetNodeSpec> targetNodeSpecs,
      Optional<TargetConfiguration> targetConfiguration,
      boolean excludeConfigurationTargets)
      throws InterruptedException {

    TargetNodeFilterForSpecResolver targetNodeFilter = TargetNodeSpec::filter;

    if (excludeConfigurationTargets) {
      targetNodeFilter =
          new TargetNodeFilterForSpecResolverWithNodeFiltering(
              targetNodeFilter, ParserWithConfigurableAttributes::filterOutNonBuildTargets);
    }

    ImmutableList<ImmutableSet<BuildTarget>> buildTargets =
        targetSpecResolver.resolveTargetSpecs(
            parsingContext.getCell(),
            targetNodeSpecs,
            targetConfiguration,
            (buildTarget, targetNode, targetType) ->
                applyDefaultFlavors(
                    buildTarget,
                    targetNode,
                    targetType,
                    parsingContext.getApplyDefaultFlavorsMode()),
            state,
            targetNodeFilter);
    if (!state.getParsingContext().excludeUnsupportedTargets()) {
      return ImmutableSet.copyOf(Iterables.concat(buildTargets));
    }
    long totalTargets = buildTargets.stream().mapToInt(targets -> targets.size()).sum();
    ImmutableSet<BuildTarget> filteredBuildTargets =
        filterIncompatibleTargetNodes(
                state,
                buildTargets.stream()
                    .flatMap(ImmutableSet::stream)
                    .map(
                        (BuildTarget target) ->
                            state.getTargetNode(target, DependencyStack.top(target))))
            .map(TargetNode::getBuildTarget)
            .collect(ImmutableSet.toImmutableSet());
    long skippedTargets = totalTargets - filteredBuildTargets.size();
    if (skippedTargets > 0) {
      this.eventBus.post(
          ConsoleEvent.warning(
              String.format(
                  "%d target%s skipped due to incompatibility with target configuration",
                  skippedTargets, skippedTargets > 1 ? "s" : "")));
    }
    return filteredBuildTargets;
  }

  private static boolean filterOutNonBuildTargets(TargetNode<?> node) {
    return node.getRuleType().isBuildRule();
  }

  @Override
  public void assertTargetIsCompatible(
      PerBuildState state, TargetNode<?> targetNode, DependencyStack dependencyStack) {
    if (!state.getParsingContext().enableTargetCompatibilityChecks()) {
      return;
    }

    Platform targetPlatform =
        state
            .getConfigurationRuleRegistry()
            .getTargetPlatformResolver()
            .getTargetPlatform(
                targetNode.getBuildTarget().getTargetConfiguration(), dependencyStack);
    if (!TargetCompatibilityChecker.targetNodeArgMatchesPlatform(
        state.getConfigurationRuleRegistry(),
        targetNode.getConstructorArg(),
        targetPlatform,
        dependencyStack,
        state.getParsingContext().getCell().getBuckConfig())) {
      BuildRuleArg argWithTargetCompatible = (BuildRuleArg) targetNode.getConstructorArg();

      StringBuilder diagnostics = new StringBuilder();
      if (!argWithTargetCompatible.getTargetCompatibleWith().isEmpty()) {
        diagnostics.append("%nTarget constraints:%n");
        argWithTargetCompatible
            .getTargetCompatibleWith()
            .forEach(
                target ->
                    diagnostics
                        .append(target.getFullyQualifiedName())
                        .append(System.lineSeparator()));
      }
      if (!argWithTargetCompatible.getCompatibleWith().isEmpty()) {
        diagnostics.append("%nTarget compatible with configurations:%n");
        argWithTargetCompatible
            .getCompatibleWith()
            .forEach(
                target ->
                    diagnostics
                        .append(target.getFullyQualifiedName())
                        .append(System.lineSeparator()));
      }

      throw new HumanReadableException(
          dependencyStack,
          "Build target %s is restricted to constraints in \"target_compatible_with\""
              + " and \"compatible_with\" that do not match the target platform %s."
              + diagnostics,
          targetNode.getBuildTarget(),
          targetPlatform);
    }
  }
}
