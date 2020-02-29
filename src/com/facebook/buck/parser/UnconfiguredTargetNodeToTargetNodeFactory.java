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
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TargetNodeMaybeIncompatible;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.rules.config.registry.ConfigurationRuleRegistry;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.RuleDescriptor;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DataTransferObjectDescriptor;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Function;

/** Creates {@link TargetNode} from {@link UnconfiguredTargetNode}. */
public class UnconfiguredTargetNodeToTargetNodeFactory
    implements ParserTargetNodeFromUnconfiguredTargetNodeFactory {

  private final TypeCoercerFactory typeCoercerFactory;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final ConstructorArgMarshaller marshaller;
  private final TargetNodeFactory targetNodeFactory;
  private final PackageBoundaryChecker packageBoundaryChecker;
  private final TargetNodeListener<TargetNode<?>> nodeListener;
  private final SelectorListResolver selectorListResolver;
  private final TargetPlatformResolver targetPlatformResolver;
  private final TargetConfigurationTransformer targetConfigurationTransformer;
  private final TargetConfiguration hostConfiguration;
  private final BuckConfig buckConfig;
  private final Optional<ConfigurationRuleRegistry> configurationRuleRegistry;

  public UnconfiguredTargetNodeToTargetNodeFactory(
      TypeCoercerFactory typeCoercerFactory,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ConstructorArgMarshaller marshaller,
      TargetNodeFactory targetNodeFactory,
      PackageBoundaryChecker packageBoundaryChecker,
      TargetNodeListener<TargetNode<?>> nodeListener,
      SelectorListResolver selectorListResolver,
      TargetPlatformResolver targetPlatformResolver,
      TargetConfigurationTransformer targetConfigurationTransformer,
      TargetConfiguration hostConfiguration,
      BuckConfig buckConfig,
      Optional<ConfigurationRuleRegistry> configurationRuleRegistry) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.marshaller = marshaller;
    this.targetNodeFactory = targetNodeFactory;
    this.packageBoundaryChecker = packageBoundaryChecker;
    this.nodeListener = nodeListener;
    this.selectorListResolver = selectorListResolver;
    this.targetPlatformResolver = targetPlatformResolver;
    this.targetConfigurationTransformer = targetConfigurationTransformer;
    this.hostConfiguration = hostConfiguration;
    this.buckConfig = buckConfig;
    this.configurationRuleRegistry = configurationRuleRegistry;
  }

  @Override
  public TargetNodeMaybeIncompatible createTargetNode(
      Cell cell,
      AbsPath buildFile,
      BuildTarget target,
      DependencyStack dependencyStack,
      UnconfiguredTargetNode unconfiguredTargetNode,
      Function<SimplePerfEvent.PerfEventId, Scope> perfEventScope) {

    KnownRuleTypes knownRuleTypes = knownRuleTypesProvider.get(cell);
    RuleType ruleType = unconfiguredTargetNode.getRuleType();
    RuleDescriptor<?> description = knownRuleTypes.getDescriptorByName(ruleType.getName());
    Cell targetCell = cell.getCell(target.getCell());

    SelectableConfigurationContext configurationContext =
        ImmutableDefaultSelectableConfigurationContext.of(
            cell.getBuckConfig(), target.getTargetConfiguration(), targetPlatformResolver);
    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
    ImmutableSortedSet.Builder<BuildTarget> configurationDeps = ImmutableSortedSet.naturalOrder();
    Object constructorArg;

    try (SimplePerfEvent.Scope scope =
        perfEventScope.apply(
            SimplePerfEvent.PerfEventId.of("MarshalledConstructorArg.convertRawAttributes"))) {

      if (configurationRuleRegistry.isPresent()) {
        Platform targetPlatform =
            configurationRuleRegistry
                .get()
                .getTargetPlatformResolver()
                .getTargetPlatform(target.getTargetConfiguration(), DependencyStack.top(target));

        if (!TargetCompatibilityChecker.configTargetsMatchPlatform(
            configurationRuleRegistry.get(),
            unconfiguredTargetNode.getCompatibleWith(),
            targetPlatform,
            dependencyStack,
            buckConfig)) {
          return TargetNodeMaybeIncompatible.ofIncompatible(
              target, unconfiguredTargetNode.getCompatibleWith());
        }
      }

      DataTransferObjectDescriptor<? extends ConstructorArg> builder =
          description.dataTransferObjectDescriptor(typeCoercerFactory);
      constructorArg =
          marshaller.populate(
              targetCell.getCellNameResolver(),
              targetCell.getFilesystem(),
              selectorListResolver,
              targetConfigurationTransformer,
              configurationContext,
              target,
              hostConfiguration,
              dependencyStack,
              builder,
              declaredDeps,
              configurationDeps,
              unconfiguredTargetNode.getAttributes());
    } catch (CoerceFailedException e) {
      throw new HumanReadableException(e, dependencyStack, e.getMessage());
    }

    target.getTargetConfiguration().getConfigurationTarget().ifPresent(configurationDeps::add);

    TargetNode<?> targetNode =
        targetNodeFactory.createFromObject(
            description.getDescription(),
            constructorArg,
            targetCell.getFilesystem(),
            target,
            dependencyStack,
            declaredDeps.build(),
            configurationDeps.build(),
            unconfiguredTargetNode.getVisibilityPatterns(),
            unconfiguredTargetNode.getWithinViewPatterns());

    packageBoundaryChecker.enforceBuckPackageBoundaries(targetCell, target, targetNode.getInputs());

    try {
      nodeListener.onCreate(buildFile.getPath(), targetNode);
    } catch (IOException e) {
      throw new HumanReadableException(e.getMessage(), e);
    }

    return TargetNodeMaybeIncompatible.ofCompatible(targetNode);
  }
}
