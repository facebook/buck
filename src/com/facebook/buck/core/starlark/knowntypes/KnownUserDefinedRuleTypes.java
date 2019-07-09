/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.starlark.knowntypes;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.model.AbstractRuleType;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.rules.knowntypes.KnownRuleTypes;
import com.facebook.buck.core.starlark.rule.SkylarkDescription;
import com.facebook.buck.core.starlark.rule.SkylarkDescriptionArg;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.core.starlark.rule.names.UserDefinedRuleNames;
import com.facebook.buck.rules.coercer.ConstructorArgBuilder;
import com.facebook.buck.rules.coercer.ImmutableConstructorArgBuilder;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.cmdline.Label;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Maps `buck.type` values to {@link SkylarkUserDefinedRule} instances. The lifetime of this object
 * is currently global per cell, however in the future we may handle invoking the parse pipeline
 * multiple times concurrently differently.
 */
public class KnownUserDefinedRuleTypes implements KnownRuleTypes {
  /** Maps an extension label to a map of rule names -> rule that defined in the parser */
  private final ConcurrentHashMap<Label, ConcurrentHashMap<String, SkylarkUserDefinedRule>>
      extensionToRules = new ConcurrentHashMap<>();

  private final SkylarkDescription description = new SkylarkDescription();

  /**
   * Adds a rule to the internal cache
   *
   * <p>NOTE: This can only be called on a rule that has been exported
   *
   * @param rule The rule to cache
   */
  public void addRule(SkylarkUserDefinedRule rule) {
    extensionToRules
        .computeIfAbsent(rule.getLabel(), label -> new ConcurrentHashMap<>())
        .put(rule.getExportedName(), rule);
  }

  /**
   * Get a rule based on its name
   *
   * @param rawBuckTypeIdentifier The name of the rule from `buck.type` in the parser, or {@link
   *     SkylarkUserDefinedRule#getName()})
   * @return The rule, or {@code null} if not found
   */
  @Nullable
  public SkylarkUserDefinedRule getRule(String rawBuckTypeIdentifier) {
    Pair<Label, String> labelAndName = UserDefinedRuleNames.fromIdentifier(rawBuckTypeIdentifier);
    if (labelAndName == null) {
      return null;
    }

    ConcurrentHashMap<String, SkylarkUserDefinedRule> rulesInExtension =
        extensionToRules.get(labelAndName.getFirst());
    if (rulesInExtension != null) {
      return rulesInExtension.get(labelAndName.getSecond());
    }
    return null;
  }

  /** Invalidates all rules found in a specific extension file */
  public void invalidateExtension(Label extension) {
    extensionToRules.remove(extension);
  }

  @Override
  public RuleType getRuleType(String name) {
    SkylarkUserDefinedRule rule = Preconditions.checkNotNull(getRule(name));
    return RuleType.of(rule.getName(), AbstractRuleType.Kind.BUILD);
  }

  @Override
  public BaseDescription<?> getDescription(RuleType ruleType) {
    return description;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> ConstructorArgBuilder<T> getConstructorArgBuilder(
      TypeCoercerFactory typeCoercerFactory,
      RuleType ruleType,
      Class<T> dtoClass,
      BuildTarget buildTarget) {
    Preconditions.checkArgument(dtoClass.isAssignableFrom(SkylarkDescriptionArg.class));
    SkylarkUserDefinedRule rule = Objects.requireNonNull(getRule(ruleType.getName()));
    return new ImmutableConstructorArgBuilder<T>(
        new SkylarkDescriptionArg(rule),
        rule.getAllParamInfo(),
        args -> {
          ((SkylarkDescriptionArg) args).build();
          // Terrible cast here, but java doesn't have useful generic type constraints
          return (T) args;
        });
  }
}
