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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import java.util.Optional;

/** The components that get contributed to a top-level run of the C++ preprocessor. */
@BuckStyleValueWithBuilder
public abstract class CxxPreprocessorInput {

  private static final CxxPreprocessorInput INSTANCE =
      ImmutableCxxPreprocessorInput.builder().build();

  public abstract Multimap<CxxSource.Type, Arg> getPreprocessorFlags();

  public abstract ImmutableList<CxxHeaders> getIncludes();

  // Framework paths.

  public abstract ImmutableSet<FrameworkPath> getFrameworks();

  // The build rules which produce headers found in the includes below.

  protected abstract ImmutableSet<BuildTarget> getRules();

  public Iterable<BuildRule> getDeps(BuildRuleResolver ruleResolver) {
    ImmutableList.Builder<BuildRule> builder = ImmutableList.builder();
    for (CxxHeaders cxxHeaders : getIncludes()) {
      cxxHeaders.getDeps(ruleResolver).forEachOrdered(builder::add);
    }
    builder.addAll(ruleResolver.getAllRules(getRules()));

    for (FrameworkPath frameworkPath : getFrameworks()) {
      if (frameworkPath.getSourcePath().isPresent()) {
        Optional<BuildRule> frameworkRule =
            ruleResolver.getRule(frameworkPath.getSourcePath().get());
        if (frameworkRule.isPresent()) {
          builder.add(frameworkRule.get());
        }
      }
    }

    for (Arg arg : getPreprocessorFlags().values()) {
      builder.addAll(BuildableSupport.getDepsCollection(arg, ruleResolver));
    }

    return builder.build();
  }

  public static CxxPreprocessorInput concat(Iterable<CxxPreprocessorInput> inputs) {
    CxxPreprocessorInput.Builder builder = CxxPreprocessorInput.builder();

    for (CxxPreprocessorInput input : inputs) {
      builder.putAllPreprocessorFlags(input.getPreprocessorFlags());
      builder.addAllIncludes(input.getIncludes());
      builder.addAllFrameworks(input.getFrameworks());
      builder.addAllRules(input.getRules());
    }

    return builder.build();
  }

  public static CxxPreprocessorInput of() {
    return INSTANCE;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableCxxPreprocessorInput.Builder {

    @Override
    public CxxPreprocessorInput build() {
      CxxPreprocessorInput cxxPreprocessorInput = super.build();
      if (cxxPreprocessorInput.equals(INSTANCE)) {
        return INSTANCE;
      }
      return cxxPreprocessorInput;
    }
  }
}
