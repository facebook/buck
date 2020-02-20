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

package com.facebook.buck.skylark.function;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasContacts;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.impl.SourceSortedSetAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.StringAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.StringListAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.StringSortedSetAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.UnconfiguredDepListAttribute;
import com.facebook.buck.core.starlark.rule.attr.impl.UnconfiguredOptionalDepAttribute;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.reflect.TypeToken;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Compute implicit parameters of a Skylark rule. These are mandatory attributes of a rule required
 * by Buck that user defined rule writers do not use (except name).
 */
class SkylarkRuleFunctionImplicitAttributes {

  private SkylarkRuleFunctionImplicitAttributes() {}

  private static void addCommon(ImmutableMap.Builder<String, Attribute<?>> builder) {
    // BuildRuleArg defines attributes of all build rules, native or user defined
    for (Method method : BuildRuleArg.class.getMethods()) {
      Optional<Pair<String, Attribute<?>>> pair = methodToAttribute(method);
      if (pair.isPresent()) {
        builder.put(pair.get().getFirst(), pair.get().getSecond());
      }
    }
  }

  static ImmutableMap<String, Attribute<?>> compute() {
    ImmutableMap.Builder<String, Attribute<?>> attrs = ImmutableMap.builder();
    addCommon(attrs);
    return attrs.build();
  }

  static ImmutableMap<String, Attribute<?>> computeTest() {
    ImmutableMap.Builder<String, Attribute<?>> attrs = ImmutableMap.builder();
    addCommon(attrs);
    for (Method method : HasContacts.class.getMethods()) {
      Optional<Pair<String, Attribute<?>>> pair = methodToAttribute(method);
      if (pair.isPresent()) {
        attrs.put(pair.get().getFirst(), pair.get().getSecond());
      }
    }

    return attrs.build();
  }

  private static Optional<Pair<String, Attribute<?>>> methodToAttribute(Method method) {
    if (method.getDeclaringClass() == Object.class) {
      // Ignore `Object` methods
      return Optional.empty();
    }
    if (method.getParameterCount() != 0) {
      // Ignore methods not getters
      return Optional.empty();
    }
    if (!method.getName().startsWith("get")) {
      // Not getters
      return Optional.empty();
    }
    String name = attrName(method);
    return Optional.of(new Pair<>(name, attributeFromMethodReturn(method)));
  }

  private static String attrName(Method method) {
    Preconditions.checkState(method.getName().startsWith("get"));
    return CaseFormat.LOWER_CAMEL.to(
        CaseFormat.LOWER_UNDERSCORE, method.getName().substring("get".length()));
  }

  private static Attribute<?> attributeFromMethodReturn(Method method) {
    // TODO(nga): obtain doc from `@Hint`
    if (method.getReturnType() == String.class) {
      return StringAttribute.of(
          "", "The name of the target", !method.isDefault(), ImmutableList.of());
    } else if (new TypeToken<ImmutableSortedSet<String>>() {}.getType()
        .equals(method.getGenericReturnType())) {
      return StringSortedSetAttribute.of(ImmutableSortedSet.of(), "", false, true);
    } else if (new TypeToken<ImmutableSet<SourcePath>>() {}.getType()
        .equals(method.getGenericReturnType())) {
      return SourceSortedSetAttribute.of(ImmutableSortedSet.of(), "", false, true);
    } else if (new TypeToken<ImmutableList<BuildTarget>>() {}.getType()
        .equals(method.getGenericReturnType())) {
      return StringListAttribute.of(ImmutableList.of(), "", false, true);
    } else if (new TypeToken<ImmutableList<UnconfiguredBuildTarget>>() {}.getType()
        .equals(method.getGenericReturnType())) {
      return UnconfiguredDepListAttribute.of(ImmutableList.of(), "", false, true);
    } else if (new TypeToken<Optional<UnconfiguredBuildTarget>>() {}.getType()
        .equals(method.getGenericReturnType())) {
      return UnconfiguredOptionalDepAttribute.of(Optional.empty(), "", false, true);
    } else {
      throw new IllegalStateException("unknown type for method: " + method);
    }
  }
}
