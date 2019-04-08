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
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.targetgraph.impl.ImmutableRawTargetNode;
import com.facebook.buck.core.model.targetgraph.raw.RawTargetNode;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypesProvider;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.function.BuckPyFunction;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.facebook.buck.rules.visibility.parser.VisibilityPatterns;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Creates {@link RawTargetNode} instances from raw data coming in form the {@link
 * ProjectBuildFileParser}.
 */
class DefaultRawTargetNodeFactory implements RawTargetNodeFactory<Map<String, Object>> {

  private final KnownRuleTypesProvider knownRuleTypesProvider;
  private final BuiltTargetVerifier builtTargetVerifier;

  public DefaultRawTargetNodeFactory(
      KnownRuleTypesProvider knownRuleTypesProvider, BuiltTargetVerifier builtTargetVerifier) {
    this.knownRuleTypesProvider = knownRuleTypesProvider;
    this.builtTargetVerifier = builtTargetVerifier;
  }

  @Override
  public RawTargetNode create(
      Cell cell,
      Path buildFile,
      UnconfiguredBuildTargetView target,
      Map<String, Object> rawAttributes) {
    KnownRuleTypes knownRuleTypes = knownRuleTypesProvider.get(cell);
    RuleType ruleType = parseRuleTypeFromRawRule(knownRuleTypes, rawAttributes);

    // Because of the way that the parser works, we know this can never return null.
    BaseDescription<?> description = knownRuleTypes.getDescription(ruleType);

    builtTargetVerifier.verifyBuildTarget(
        cell, ruleType, buildFile, target, description, rawAttributes);

    ImmutableSet<VisibilityPattern> visibilityPatterns =
        VisibilityPatterns.createFromStringList(
            cell.getCellPathResolver(),
            "visibility",
            rawAttributes.get("visibility"),
            target.getData());
    ImmutableSet<VisibilityPattern> withinViewPatterns =
        VisibilityPatterns.createFromStringList(
            cell.getCellPathResolver(),
            "within_view",
            rawAttributes.get("within_view"),
            target.getData());

    return ImmutableRawTargetNode.of(
        target.getData(),
        ruleType,
        ImmutableMap.copyOf(rawAttributes),
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
