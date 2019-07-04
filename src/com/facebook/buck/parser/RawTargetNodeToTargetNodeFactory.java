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
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.TargetConfigurationTransformer;
import com.facebook.buck.core.model.platform.ConstraintResolver;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.impl.TargetNodeFactory;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.select.SelectorListResolver;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.event.SimplePerfEvent.Scope;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.ConstructorArgBuilder;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Function;

/** Creates {@link TargetNode} from {@link RawTargetNode}. */
public class RawTargetNodeToTargetNodeFactory implements ParserTargetNodeFactory<RawTargetNode> {

  private final TypeCoercerFactory typeCoercerFactory;
  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final ConstructorArgMarshaller marshaller;
  private final TargetNodeFactory targetNodeFactory;
  private final PackageBoundaryChecker packageBoundaryChecker;
  private final TargetNodeListener<TargetNode<?>> nodeListener;
  private final SelectorListResolver selectorListResolver;
  private final ConstraintResolver constraintResolver;
  private final TargetPlatformResolver targetPlatformResolver;
  private final TargetConfigurationTransformer targetConfigurationTransformer;

  public RawTargetNodeToTargetNodeFactory(
      TypeCoercerFactory typeCoercerFactory,
      KnownRuleTypesProvider knownRuleTypesProvider,
      ConstructorArgMarshaller marshaller,
      TargetNodeFactory targetNodeFactory,
      PackageBoundaryChecker packageBoundaryChecker,
      TargetNodeListener<TargetNode<?>> nodeListener,
      SelectorListResolver selectorListResolver,
      ConstraintResolver constraintResolver,
      TargetPlatformResolver targetPlatformResolver,
      TargetConfigurationTransformer targetConfigurationTransformer) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.marshaller = marshaller;
    this.targetNodeFactory = targetNodeFactory;
    this.packageBoundaryChecker = packageBoundaryChecker;
    this.nodeListener = nodeListener;
    this.selectorListResolver = selectorListResolver;
    this.constraintResolver = constraintResolver;
    this.targetPlatformResolver = targetPlatformResolver;
    this.targetConfigurationTransformer = targetConfigurationTransformer;
  }

  @Override
  public TargetNode<?> createTargetNode(
      Cell cell,
      Path buildFile,
      BuildTarget target,
      RawTargetNode rawTargetNode,
      Function<PerfEventId, Scope> perfEventScope) {

    KnownRuleTypes knownRuleTypes = knownRuleTypesProvider.get(cell);
    RuleType ruleType = rawTargetNode.getRuleType();
    BaseDescription<?> description = knownRuleTypes.getDescription(ruleType);
    Cell targetCell = cell.getCell(target);

    SelectableConfigurationContext configurationContext =
        DefaultSelectableConfigurationContext.of(
            cell.getBuckConfig(),
            constraintResolver,
            target.getTargetConfiguration(),
            targetPlatformResolver);
    ImmutableSet.Builder<BuildTarget> declaredDeps = ImmutableSet.builder();
    ImmutableSortedSet.Builder<BuildTarget> configurationDeps = ImmutableSortedSet.naturalOrder();
    Object constructorArg;
    try (SimplePerfEvent.Scope scope =
        perfEventScope.apply(PerfEventId.of("MarshalledConstructorArg.convertRawAttributes"))) {
      ConstructorArgBuilder<?> builder =
          knownRuleTypes.getConstructorArgBuilder(
              typeCoercerFactory, ruleType, description.getConstructorArgType(), target);
      constructorArg =
          marshaller.populateWithConfiguringAttributes(
              targetCell.getCellPathResolver(),
              targetCell.getFilesystem(),
              selectorListResolver,
              targetConfigurationTransformer,
              configurationContext,
              target,
              builder,
              declaredDeps,
              configurationDeps,
              rawTargetNode.getAttributes());
    } catch (CoerceFailedException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }

    configurationDeps.addAll(target.getTargetConfiguration().getConfigurationTargets());

    TargetNode<?> targetNode =
        targetNodeFactory.createFromObject(
            description,
            constructorArg,
            targetCell.getFilesystem(),
            target,
            declaredDeps.build(),
            configurationDeps.build(),
            rawTargetNode.getVisibilityPatterns(),
            rawTargetNode.getWithinViewPatterns(),
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
