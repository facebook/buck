/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import java.util.Optional;
import org.immutables.value.Value;

/** The components that get contributed to a top-level run of the C++ preprocessor. */
@Value.Immutable(singleton = true)
@BuckStyleImmutable
abstract class AbstractCxxPreprocessorInput {

  @Value.Parameter
  public abstract Multimap<CxxSource.Type, Arg> getPreprocessorFlags();

  @Value.Parameter
  public abstract ImmutableList<CxxHeaders> getIncludes();

  // Framework paths.
  @Value.Parameter
  public abstract ImmutableSet<FrameworkPath> getFrameworks();

  // The build rules which produce headers found in the includes below.
  @Value.Parameter
  protected abstract ImmutableSet<BuildTarget> getRules();

  public Iterable<BuildRule> getDeps(
      BuildRuleResolver ruleResolver, SourcePathRuleFinder ruleFinder) {
    ImmutableList.Builder<BuildRule> builder = ImmutableList.builder();
    for (CxxHeaders cxxHeaders : getIncludes()) {
      cxxHeaders.getDeps(ruleFinder).forEachOrdered(builder::add);
    }
    builder.addAll(ruleResolver.getAllRules(getRules()));

    for (FrameworkPath frameworkPath : getFrameworks()) {
      if (frameworkPath.getSourcePath().isPresent()) {
        Optional<BuildRule> frameworkRule = ruleFinder.getRule(frameworkPath.getSourcePath().get());
        if (frameworkRule.isPresent()) {
          builder.add(frameworkRule.get());
        }
      }
    }

    for (Arg arg : getPreprocessorFlags().values()) {
      builder.addAll(BuildableSupport.getDepsCollection(arg, ruleFinder));
    }

    return builder.build();
  }

  public static CxxPreprocessorInput concat(Iterable<CxxPreprocessorInput> inputs) {
    ImmutableMultimap.Builder<CxxSource.Type, Arg> preprocessorFlags = ImmutableMultimap.builder();
    ImmutableList.Builder<CxxHeaders> headers = ImmutableList.builder();
    ImmutableSet.Builder<FrameworkPath> frameworks = ImmutableSet.builder();
    ImmutableSet.Builder<BuildTarget> rules = ImmutableSet.builder();

    for (CxxPreprocessorInput input : inputs) {
      preprocessorFlags.putAll(input.getPreprocessorFlags());
      headers.addAll(input.getIncludes());
      frameworks.addAll(input.getFrameworks());
      rules.addAll(input.getRules());
    }

    return CxxPreprocessorInput.of(
        preprocessorFlags.build(), headers.build(), frameworks.build(), rules.build());
  }
}
