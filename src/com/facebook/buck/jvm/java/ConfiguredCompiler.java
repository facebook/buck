/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

/** Represents a compiler bundled with its basic configuration (boot classpath and the like). */
public interface ConfiguredCompiler extends AddsToRuleKey {
  default BuildRuleParams addInputs(BuildRuleParams params, SourcePathRuleFinder ruleFinder) {
    return params
        .withDeclaredDeps(
            () ->
                ImmutableSortedSet.copyOf(
                    Iterables.concat(params.getDeclaredDeps().get(), getDeclaredDeps(ruleFinder))))
        .withExtraDeps(
            () ->
                ImmutableSortedSet.copyOf(
                    Iterables.concat(params.getExtraDeps().get(), getExtraDeps(ruleFinder))));
  }

  default Iterable<BuildRule> getDeclaredDeps(
      @SuppressWarnings("unused") SourcePathRuleFinder ruleFinder) {
    return ImmutableList.of();
  }

  default Iterable<BuildRule> getExtraDeps(SourcePathRuleFinder ruleFinder) {
    return BuildableSupport.getDepsCollection(getCompiler(), ruleFinder);
  }

  default Iterable<BuildRule> getBuildDeps(SourcePathRuleFinder ruleFinder) {
    return Iterables.concat(getDeclaredDeps(ruleFinder), getExtraDeps(ruleFinder));
  }

  Tool getCompiler();
}
