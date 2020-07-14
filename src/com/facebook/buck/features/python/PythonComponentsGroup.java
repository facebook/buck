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

package com.facebook.buck.features.python;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.util.LinkedHashMap;
import java.util.Map;

@BuckStyleValue
public abstract class PythonComponentsGroup implements AddsToRuleKey {

  // Python modules as map of their module name to location of the source.
  @AddToRuleKey
  public abstract ImmutableMap<BuildTarget, ImmutableList<PythonComponents>> getComponents();

  public Iterable<PythonComponents> values() {
    return Iterables.concat(getComponents().values());
  }

  public PythonResolvedComponentsGroup resolve(
      SourcePathResolverAdapter resolver, boolean canAccessComponentContents) {
    ImmutablePythonResolvedComponentsGroup.Builder builder =
        ImmutablePythonResolvedComponentsGroup.builder();
    builder.setCanAccessComponentContents(canAccessComponentContents);
    getComponents()
        .forEach(
            (target, components) ->
                components.forEach(
                    c -> builder.putComponents(target, c.resolvePythonComponents(resolver))));
    return builder.build();
  }

  public static class Builder {

    private final Map<BuildTarget, ImmutableList.Builder<PythonComponents>> allComponents =
        new LinkedHashMap<>();

    public Builder putComponent(BuildTarget owner, PythonComponents components) {
      if (!allComponents.containsKey(owner)) {
        allComponents.put(owner, ImmutableList.builder());
      }
      allComponents.get(owner).add(components);
      return this;
    }

    public ImmutablePythonComponentsGroup build() {
      return ImmutablePythonComponentsGroup.ofImpl(
          allComponents.entrySet().stream()
              .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, e -> e.getValue().build())));
    }
  }
}
