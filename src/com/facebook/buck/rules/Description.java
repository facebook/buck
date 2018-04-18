/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.rules.coercer.ConstructorArgMarshaller;
import com.facebook.buck.util.MoreStrings;
import com.google.common.base.CaseFormat;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * The Source of Truth about a {@link BuildRule}, providing mechanisms to expose the arguments that
 * rules derived from the Buildable take and providing a factory for those BuildRules. It is
 * expected that instances of this class are stateless.
 *
 * @param <T> The object describing the parameters to be passed to the {@link BuildRule}. How this
 *     is processed is described in the class level javadoc of {@link ConstructorArgMarshaller}.
 */
public interface Description<T> {

  LoadingCache<Class<? extends Description<?>>, BuildRuleType> BUILD_RULE_TYPES_BY_CLASS =
      CacheBuilder.newBuilder()
          .build(
              new CacheLoader<Class<? extends Description<?>>, BuildRuleType>() {
                @Override
                public BuildRuleType load(Class<? extends Description<?>> key) {
                  return Description.getBuildRuleType(key.getSimpleName());
                }
              });

  /** @return The {@link BuildRuleType} being described. */
  static BuildRuleType getBuildRuleType(Class<? extends Description<?>> descriptionClass) {
    return BUILD_RULE_TYPES_BY_CLASS.getUnchecked(descriptionClass);
  }

  @SuppressWarnings("unchecked")
  static BuildRuleType getBuildRuleType(Description<?> description) {
    return getBuildRuleType((Class<? extends Description<?>>) description.getClass());
  }

  static BuildRuleType getBuildRuleType(String descriptionClassName) {
    descriptionClassName =
        MoreStrings.stripPrefix(descriptionClassName, "Abstract").orElse(descriptionClassName);
    descriptionClassName =
        MoreStrings.stripSuffix(descriptionClassName, "Description").orElse(descriptionClassName);
    return BuildRuleType.of(
        CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, descriptionClassName));
  }

  /** The class of the argument of this Description uses in createBuildRule(). */
  Class<T> getConstructorArgType();

  /**
   * Create a {@link BuildRule} for the given {@link BuildRuleParams}. Note that the {@link
   * com.facebook.buck.model.BuildTarget} referred to in the {@code params} contains the {@link
   * Flavor} to create.
   *
   * @param buildTarget
   * @param args A constructor argument, of type as returned by {@link #getConstructorArgType()}.
   * @return The {@link BuildRule} that describes the default flavour of the rule being described.
   */
  BuildRule createBuildRule(
      BuildRuleCreationContext context, BuildTarget buildTarget, BuildRuleParams params, T args);

  /**
   * Whether or not the build rule subgraph produced by this {@code Description} is safe to cache in
   * {@link IncrementalActionGraphGenerator} for incremental action graph generation.
   */
  default boolean producesCacheableSubgraph() {
    return false;
  }
}
