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

package com.facebook.buck.core.starlark.rule;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.starlark.coercer.SkylarkDescriptionArgBuilder;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.syntax.BaseFunction;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Description arg for user defined rules. Instead of using reflection and immutables, this class
 * uses a backing store of attribute names -> coerced values, and makes the user's implementation
 * function available
 */
public class SkylarkDescriptionArg implements SkylarkDescriptionArgBuilder, BuildRuleArg {

  private boolean attrValuesAreMutable = true;
  private final SkylarkUserDefinedRule rule;
  private final Map<String, Object> coercedAttrValues;
  @Nullable private String name;
  @Nullable private ImmutableList<UnconfiguredBuildTarget> compatibleWith;
  @Nullable private Optional<UnconfiguredBuildTarget> defaultTargetPlatform;

  /**
   * Create an instance of {@link SkylarkDescriptionArg}
   *
   * @param rule the rule that should be used to determine acceptable attributes for a target, and
   *     to provide access to the user's implementation function
   */
  public SkylarkDescriptionArg(SkylarkUserDefinedRule rule) {
    this.rule = rule;
    this.coercedAttrValues = new HashMap<>(rule.getAttrs().size());
  }

  @Override
  public void setPostCoercionValue(String attr, Object value) {
    Preconditions.checkState(
        rule.getAttrs().containsKey(attr),
        "Tried to set attribute %s, but it was not one of the attributes for %s",
        attr,
        rule.getName());
    Preconditions.checkState(
        attrValuesAreMutable,
        "Tried to set attribute %s value after building an instance of %s",
        attr,
        rule.getName());
    coercedAttrValues.put(attr, value);
  }

  @Override
  public Object getPostCoercionValue(String attr) {
    return Preconditions.checkNotNull(
        coercedAttrValues.get(attr),
        "Tried to get value of an attribute '%s' that did not have a value set yet",
        attr);
  }

  /**
   * 'Build' the {@link SkylarkDescriptionArg}. After this has been called, {@link
   * #setPostCoercionValue(String, Object)} may not be called.
   */
  @SuppressWarnings("unchecked")
  public void build() {
    attrValuesAreMutable = false;
    name = (String) Preconditions.checkNotNull(coercedAttrValues.get("name"));
    compatibleWith =
        (ImmutableList<UnconfiguredBuildTarget>)
            Preconditions.checkNotNull(
                coercedAttrValues.getOrDefault("compatible_with", ImmutableList.of()));
    defaultTargetPlatform =
        (Optional<UnconfiguredBuildTarget>)
            coercedAttrValues.getOrDefault("default_target_platform", Optional.empty());
  }

  /**
   * Get the {@link SkylarkDescriptionArg} that has information about parameters and the user's
   * implementation function
   */
  public SkylarkUserDefinedRule getRule() {
    return rule;
  }

  SkylarkRuleContextAttr getCoercedAttrValues(RuleAnalysisContext context) {
    Preconditions.checkState(
        !attrValuesAreMutable,
        "Should not get Coerced Attrs until after the DescriptionArg is frozen.");

    Map<String, Object> filteredCoercedAttrs =
        Maps.filterKeys(
            coercedAttrValues, Predicates.not(rule.getHiddenImplicitAttributes()::contains));
    Map<String, Attribute<?>> filteredAttrs =
        Maps.filterKeys(
            rule.getAttrs(), Predicates.not(rule.getHiddenImplicitAttributes()::contains));

    return SkylarkRuleContextAttr.of(
        rule.getExportedName(), filteredCoercedAttrs, filteredAttrs, context);
  }

  @Override
  public String getName() {
    return Preconditions.checkNotNull(name);
  }

  @Override
  public Optional<UnconfiguredBuildTarget> getDefaultTargetPlatform() {
    return Preconditions.checkNotNull(defaultTargetPlatform);
  }

  public ImmutableMap<String, ParamInfo<?>> getAllParamInfo() {
    return rule.getAllParamInfo();
  }

  public BaseFunction getImplementation() {
    return rule.getImplementation();
  }

  @Override
  @SuppressWarnings("unchecked")
  public ImmutableSet<SourcePath> getLicenses() {
    // Unchecked as we validate this type with the Attribute
    return ((ImmutableSortedSet<SourcePath>) getPostCoercionValue("licenses"));
  }

  @Override
  @SuppressWarnings("unchecked")
  public ImmutableSortedSet<String> getLabels() {
    // Unchecked as we validate this type with the Attribute
    return (ImmutableSortedSet<String>) getPostCoercionValue("labels");
  }

  /** @return contacts for this rule, or an empty set of `contacts` was not set */
  @SuppressWarnings("unchecked")
  public ImmutableSortedSet<String> getContacts() {
    // Unchecked as we validate this type with the Attribute
    Object rawValue = getPostCoercionValue("contacts");
    if (rawValue == null) {
      return ImmutableSortedSet.of();
    } else {
      return (ImmutableSortedSet<String>) rawValue;
    }
  }

  @Override
  public ImmutableList<UnconfiguredBuildTarget> getCompatibleWith() {
    return Preconditions.checkNotNull(compatibleWith);
  }
}
