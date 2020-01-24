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
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
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
import java.nio.file.Path;
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
      TargetConfiguration hostConfiguration) {
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
  }

  @Override
  public TargetNode<?> createTargetNode(
      Cell cell,
      Path buildFile,
      BuildTarget target,
      DependencyStack dependencyStack,
      UnconfiguredTargetNode unconfiguredTargetNode,
      Function<SimplePerfEvent.PerfEventId, Scope> perfEventScope) {

    KnownRuleTypes knownRuleTypes = knownRuleTypesProvider.get(cell);
    RuleType ruleType = unconfiguredTargetNode.getRuleType();
    BaseDescription<?> description = knownRuleTypes.getDescription(ruleType);
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
      DataTransferObjectDescriptor<? extends ConstructorArg> builder =
          knownRuleTypes.getConstructorArgDescriptor(
              typeCoercerFactory, ruleType, description.getConstructorArgType());
      constructorArg =
          marshaller.populate(
              targetCell.getCellPathResolver(),
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
            description,
            constructorArg,
            targetCell.getFilesystem(),
            target,
            dependencyStack,
            declaredDeps.build(),
            configurationDeps.build(),
            unconfiguredTargetNode.getVisibilityPatterns(),
            unconfiguredTargetNode.getWithinViewPatterns(),
            targetCell.getCellPathResolver());

    packageBoundaryChecker.enforceBuckPackageBoundaries(targetCell, target, targetNode.getInputs());

    try {
      nodeListener.onCreate(buildFile, targetNode);
    } catch (IOException e) {
      throw new HumanReadableException(e.getMessage(), e);
    }

    return targetNode;
  }
}
