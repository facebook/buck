/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.features.haskell;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.cxx.CxxGenruleDescription;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.util.Map;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractHaskellSources implements AddsToRuleKey {

  @AddToRuleKey
  @Value.NaturalOrder
  abstract ImmutableSortedMap<String, SourcePath> getModuleMap();

  public static HaskellSources from(
      BuildTarget target,
      ActionGraphBuilder graphBuilder,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      HaskellPlatform platform,
      String parameter,
      SourceSortedSet sources) {
    HaskellSources.Builder builder = HaskellSources.builder();
    for (Map.Entry<String, SourcePath> ent :
        sources.toNameMap(target, pathResolver, parameter).entrySet()) {
      builder.putModuleMap(
          ent.getKey().substring(0, ent.getKey().lastIndexOf('.')).replace(File.separatorChar, '.'),
          CxxGenruleDescription.fixupSourcePath(
              graphBuilder, ruleFinder, platform.getCxxPlatform(), ent.getValue()));
    }
    return builder.build();
  }

  public ImmutableSortedSet<String> getModuleNames() {
    return getModuleMap().keySet();
  }

  public ImmutableCollection<SourcePath> getSourcePaths() {
    return getModuleMap().values();
  }

  public Iterable<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return ruleFinder.filterBuildRuleInputs(getSourcePaths());
  }
}
