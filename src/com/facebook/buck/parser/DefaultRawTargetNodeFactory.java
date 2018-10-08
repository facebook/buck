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
import com.facebook.buck.core.model.targetgraph.RawAttributes;
import com.facebook.buck.core.model.targetgraph.RawTargetNode;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableRawTargetNode;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.event.PerfEventId;
import com.facebook.buck.event.SimplePerfEvent;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.function.BuckPyFunction;
import com.facebook.buck.rules.coercer.CoerceFailedException;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.rules.visibility.VisibilityPatterns;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Creates {@link RawTargetNode} instances from raw data coming in form the {@link
 * ProjectBuildFileParser}.
 */
class DefaultRawTargetNodeFactory implements RawTargetNodeFactory<Map<String, Object>> {

  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final ConstructorArgMarshaller marshaller;
  private final BuiltTargetVerifier builtTargetVerifier;

  public DefaultRawTargetNodeFactory(
      KnownRuleTypesProvider knownRuleTypesProvider,
      ConstructorArgMarshaller marshaller,
      BuiltTargetVerifier builtTargetVerifier) {
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.marshaller = marshaller;
    this.builtTargetVerifier = builtTargetVerifier;
  }

  @Override
  public RawTargetNode create(
      Cell cell,
      Path buildFile,
      BuildTarget target,
      Map<String, Object> rawAttributes,
      Function<PerfEventId, SimplePerfEvent.Scope> perfEventScope) {
    KnownRuleTypes knownRuleTypes = knownRuleTypesProvider.get(cell);
    RuleType ruleType = parseRuleTypeFromRawRule(knownRuleTypes, rawAttributes);

    // Because of the way that the parser works, we know this can never return null.
    BaseDescription<?> description = knownRuleTypes.getDescription(ruleType);

    builtTargetVerifier.verifyBuildTarget(
        cell, ruleType, buildFile, target, description, rawAttributes);

    ImmutableMap<String, Object> populatedAttributes;
    ImmutableSet<VisibilityPattern> visibilityPatterns;
    ImmutableSet<VisibilityPattern> withinViewPatterns;
    Cell targetCell = cell.getCell(target);
    try (SimplePerfEvent.Scope scope =
        perfEventScope.apply(PerfEventId.of("MarshalledConstructorArg.convertRawAttributes"))) {
      populatedAttributes =
          marshaller.convertRawAttributes(
              targetCell.getCellPathResolver(),
              targetCell.getFilesystem(),
              target,
              description.getConstructorArgType(),
              rawAttributes);
      visibilityPatterns =
          VisibilityPatterns.createFromStringList(
              cell.getCellPathResolver(), "visibility", rawAttributes.get("visibility"), target);
      withinViewPatterns =
          VisibilityPatterns.createFromStringList(
              cell.getCellPathResolver(), "within_view", rawAttributes.get("within_view"), target);
    } catch (CoerceFailedException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }

    return ImmutableRawTargetNode.of(
        target,
        ruleType,
        new RawAttributes(populatedAttributes),
        visibilityPatterns,
        withinViewPatterns);
  }

  private static RuleType parseRuleTypeFromRawRule(
      KnownRuleTypes knownRuleTypes, Map<String, Object> attributes) {
    String type =
        (String) Objects.requireNonNull(attributes.get(BuckPyFunction.TYPE_PROPERTY_NAME));
    return knownRuleTypes.getRuleType(type);
  }
}
