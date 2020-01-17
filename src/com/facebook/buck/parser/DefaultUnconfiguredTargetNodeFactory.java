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
import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableUnconfiguredTargetNode;
import com.facebook.buck.core.model.targetgraph.impl.Package;
import com.facebook.buck.core.model.targetgraph.raw.UnconfiguredTargetNode;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.provider.KnownRuleTypesProvider;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.select.impl.SelectorListFactory;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.function.BuckPyFunction;
import com.facebook.buck.parser.syntax.ListWithSelects;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.visibility.VisibilityAttributes;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.rules.visibility.parser.VisibilityPatterns;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Creates {@link UnconfiguredTargetNode} instances from raw data coming in form the {@link
 * ProjectBuildFileParser}.
 */
public class DefaultUnconfiguredTargetNodeFactory implements UnconfiguredTargetNodeFactory {

  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final BuiltTargetVerifier builtTargetVerifier;
  private final CellPathResolver cellPathResolver;
  private final SelectorListFactory selectorListFactory;

  public DefaultUnconfiguredTargetNodeFactory(
      KnownRuleTypesProvider knownRuleTypesProvider,
      BuiltTargetVerifier builtTargetVerifier,
      CellPathResolver cellPathResolver,
      SelectorListFactory selectorListFactory) {
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.builtTargetVerifier = builtTargetVerifier;
    this.cellPathResolver = cellPathResolver;
    this.selectorListFactory = selectorListFactory;
  }

  private ImmutableMap<String, Object> convertSelects(
      Map<String, Object> attrs,
      ForwardRelativePath pathRelativeToProjectRoot,
      DependencyStack dependencyStack) {
    ImmutableMap.Builder<String, Object> result = ImmutableMap.builder();
    for (Map.Entry<String, Object> attr : attrs.entrySet()) {
      result.put(
          attr.getKey(),
          convertSelectorListInAttrValue(
              attr.getKey(), attr.getValue(), pathRelativeToProjectRoot, dependencyStack));
    }
    return result.build();
  }

  /**
   * Convert attr value {@link ListWithSelects} to {@link SelectorList} or keep it as is otherwise
   */
  private Object convertSelectorListInAttrValue(
      String attrName,
      Object attrValue,
      ForwardRelativePath pathRelativeToProjectRoot,
      DependencyStack dependencyStack) {
    if (attrValue instanceof ListWithSelects) {
      try {
        return selectorListFactory.create(
            cellPathResolver, pathRelativeToProjectRoot, (ListWithSelects) attrValue);
      } catch (CoerceFailedException e) {
        throw new HumanReadableException(
            e,
            dependencyStack,
            "failed to coerce list with selects for attr %s: %s",
            attrName,
            e.getMessage());
      }
    } else {
      return attrValue;
    }
  }

  @Override
  public UnconfiguredTargetNode create(
      Cell cell,
      Path buildFile,
      UnconfiguredBuildTargetView target,
      DependencyStack dependencyStack,
      Map<String, Object> rawAttributes,
      Package pkg) {
    KnownRuleTypes knownRuleTypes = knownRuleTypesProvider.get(cell);
    RuleType ruleType = parseRuleTypeFromRawRule(knownRuleTypes, rawAttributes);

    if (ruleType.getKind() == RuleType.Kind.CONFIGURATION) {
      assertRawTargetNodeAttributesNotConfigurable(target, rawAttributes);
    }

    // Because of the way that the parser works, we know this can never return null.
    BaseDescription<?> description = knownRuleTypes.getDescription(ruleType);

    builtTargetVerifier.verifyBuildTarget(
        cell, ruleType, buildFile, target, description, rawAttributes);

    String visibilityDefinerDescription = target.getData().getFullyQualifiedName();

    ImmutableSet<VisibilityPattern> visibilityPatterns =
        VisibilityPatterns.createFromStringList(
            cell.getCellPathResolver(),
            VisibilityAttributes.VISIBILITY,
            rawAttributes.get(VisibilityAttributes.VISIBILITY),
            buildFile,
            () -> visibilityDefinerDescription);

    if (visibilityPatterns.isEmpty()) {
      visibilityPatterns = pkg.getVisibilityPatterns();
    }

    ImmutableSet<VisibilityPattern> withinViewPatterns =
        VisibilityPatterns.createFromStringList(
            cell.getCellPathResolver(),
            VisibilityAttributes.WITHIN_VIEW,
            rawAttributes.get(VisibilityAttributes.WITHIN_VIEW),
            buildFile,
            () -> visibilityDefinerDescription);

    if (withinViewPatterns.isEmpty()) {
      withinViewPatterns = pkg.getWithinViewPatterns();
    }

    ImmutableMap<String, Object> withSelects =
        convertSelects(rawAttributes, target.getCellRelativeBasePath().getPath(), dependencyStack);

    return ImmutableUnconfiguredTargetNode.of(
        target.getData(), ruleType, withSelects, visibilityPatterns, withinViewPatterns);
  }

  private static RuleType parseRuleTypeFromRawRule(
      KnownRuleTypes knownRuleTypes, Map<String, Object> attributes) {
    String type =
        (String) Objects.requireNonNull(attributes.get(BuckPyFunction.TYPE_PROPERTY_NAME));
    return knownRuleTypes.getRuleType(type);
  }

  private void assertRawTargetNodeAttributesNotConfigurable(
      UnconfiguredBuildTargetView buildTarget, Map<String, Object> rawTargetNodeAttributes) {
    for (Map.Entry<String, ?> entry : rawTargetNodeAttributes.entrySet()) {
      Preconditions.checkState(
          !(entry.getValue() instanceof SelectorList),
          "Attribute %s cannot be configurable in %s",
          entry.getKey(),
          buildTarget);
    }
  }
}
