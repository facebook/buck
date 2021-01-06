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

package com.facebook.buck.core.rules.knowntypes;

import com.facebook.buck.core.description.BaseDescription;
import com.facebook.buck.core.description.Description;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.description.impl.DescriptionCache;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.rules.config.ConfigurationRuleArg;
import com.facebook.buck.core.rules.config.ConfigurationRuleDescription;
import com.facebook.buck.core.rules.providers.impl.BuiltInProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import org.immutables.value.Value;

/** Provides access to rule types. */
@BuckStyleValue
public abstract class KnownNativeRuleTypes implements KnownRuleTypes {

  public abstract ImmutableList<Description<?>> getKnownBuildDescriptions();

  public abstract ImmutableList<ConfigurationRuleDescription<?, ?>>
      getKnownConfigurationDescriptions();

  public abstract ImmutableList<BuiltInProvider<?>> getPerFeatureProviders();

  @Value.Lazy
  public ImmutableMap<String, RuleType> getNativeTypesByName() {
    return getDescriptions().stream()
        .map(DescriptionCache::getRuleType)
        .collect(ImmutableMap.toImmutableMap(RuleType::getName, t -> t));
  }

  /** @return all known descriptions */
  @Value.Lazy
  public ImmutableList<BaseDescription<?>> getDescriptions() {
    return ImmutableList.<BaseDescription<?>>builder()
        .addAll(getKnownBuildDescriptions())
        .addAll(getKnownConfigurationDescriptions())
        .build();
  }

  /** @return all descriptions organized by their {@link RuleType}. */
  @Value.Lazy
  protected ImmutableMap<RuleType, BaseDescription<?>> getDescriptionsByRule() {
    return getDescriptions().stream()
        .collect(ImmutableMap.toImmutableMap(DescriptionCache::getRuleType, Function.identity()));
  }

  @Override
  public RuleDescriptor<?> getDescriptorByName(String name) {
    return getDescriptorByNameImpl(name);
  }

  @SuppressWarnings("unchecked")
  private <T extends ConstructorArg> RuleDescriptor<T> getDescriptorByNameImpl(String name) {
    RuleType type = getNativeTypesByName().get(name);
    if (type == null) {
      throw new HumanReadableException("Unable to find rule type: %s", name);
    }
    BaseDescription<T> description =
        (BaseDescription<T>)
            Preconditions.checkNotNull(
                getDescriptionsByRule().get(type), "Cannot find a description for type %s", type);
    return RuleDescriptor.of(
        type,
        description,
        typeCoercerFactory ->
            typeCoercerFactory.getConstructorArgDescriptor(description.getConstructorArgType()));
  }

  // Verify that there are no duplicate rule types being defined.
  @Value.Check
  protected void check() {
    Set<RuleType> types = new HashSet<>();
    for (BaseDescription<?> description : getDescriptions()) {
      checkDescription(description);

      RuleType type = DescriptionCache.getRuleType(description);
      if (!types.add(DescriptionCache.getRuleType(description))) {
        throw new IllegalStateException(String.format("multiple descriptions with type %s", type));
      }
    }
  }

  private static void checkDescription(BaseDescription<?> description) {
    boolean isBuildRule = BuildRuleArg.class.isAssignableFrom(description.getConstructorArgType());
    boolean isConfiguration =
        ConfigurationRuleArg.class.isAssignableFrom(description.getConstructorArgType());
    Preconditions.checkArgument(
        isBuildRule != isConfiguration,
        "constructor arg must be either build or configuration: %s",
        description.getConstructorArgType().getName());
  }

  public static KnownNativeRuleTypes of(
      ImmutableList<Description<?>> knownBuildDescriptions,
      ImmutableList<ConfigurationRuleDescription<?, ?>> knownConfigurationDescriptions,
      ImmutableList<BuiltInProvider<?>> perFeatureProviders) {
    return ImmutableKnownNativeRuleTypes.of(
        knownBuildDescriptions, knownConfigurationDescriptions, perFeatureProviders);
  }
}
