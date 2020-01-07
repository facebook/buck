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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.cxx.CxxGenruleDescription;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.util.Map;
import org.immutables.value.Value;

@BuckStyleValue
abstract class HaskellSources implements AddsToRuleKey {

  @AddToRuleKey
  @Value.NaturalOrder
  abstract ImmutableSortedMap<HaskellSourceModule, SourcePath> getModuleMap();

  public static HaskellSources from(
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      HaskellPlatform platform,
      String parameter,
      SourceSortedSet sources) {
    ImmutableMap<String, SourcePath> namedMap =
        sources.toNameMap(target, graphBuilder.getSourcePathResolver(), parameter);

    ImmutableMap.Builder<HaskellSourceModule, SourcePath> moduleMap =
        ImmutableMap.builderWithExpectedSize(namedMap.size());

    for (Map.Entry<String, SourcePath> ent :
        sources.toNameMap(target, graphBuilder.getSourcePathResolver(), parameter).entrySet()) {
      moduleMap.put(
          HaskellSourceModule.from(ent.getKey()),
          CxxGenruleDescription.fixupSourcePath(
              graphBuilder, platform.getCxxPlatform(), ent.getValue()));
    }
    return ImmutableHaskellSources.of(moduleMap.build());
  }

  public ImmutableSortedSet<String> getModuleNames() {
    ImmutableSortedSet.Builder<String> builder = ImmutableSortedSet.naturalOrder();
    for (HaskellSourceModule module : getModuleMap().keySet()) {
      if (module.getSourceType() == HaskellSourceModule.SourceType.HsSrcFile) {
        builder.add(module.getModuleName());
      }
    }
    return builder.build();
  }

  public ImmutableCollection<SourcePath> getSourcePaths() {
    ImmutableCollection.Builder<SourcePath> builder = ImmutableList.builder();
    for (Map.Entry<HaskellSourceModule, SourcePath> ent : getModuleMap().entrySet()) {
      // The compiler does not expect the .hs-boot file to be passed as argument. It must live in
      // the same directory as its parent source file .hs instead no matter what the actual import
      // directory list is.
      if (ent.getKey().getSourceType() == HaskellSourceModule.SourceType.HsSrcFile) {
        builder.add(ent.getValue());
      }
    }
    return builder.build();
  }

  public Iterable<String> getOutputPaths(String suffix) {
    return Iterables.transform(getModuleMap().keySet(), m -> m.getOutputPath(suffix));
  }

  public Iterable<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return ruleFinder.filterBuildRuleInputs(getModuleMap().values());
  }
}
